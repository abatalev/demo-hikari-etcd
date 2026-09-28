# Механизм: от `etcdctl put` до нового числа коннектов

Документ описывает весь путь изменения конфигурации и все решения, которые на этом пути
принимаются. Ссылки на строки — на текущее состояние кода, при правках могут сдвинуться.

## Путь целиком

```
etcdctl put /config/pool-service/hikari/maximumPoolSize 25
     │
     ▼
jetcd: событие watch (WatchResponse с батчем событий)
     │  EtcdPoolConfigSource.onEvent()            etcd/EtcdPoolConfigSource.java:213
     │    keys.put(key, value) или keys.remove(key) — полное состояние префикса в памяти
     ▼
EtcdKeys.parse(keys, prefix())                     etcd/EtcdKeys.java:45
     │    ключи → HikariSettings; нечитаемое значение → в problems, остальное применяется
     ▼
.settings().resolve(defaults)                      pool/HikariSettings.java:46
     │    null → локальный дефолт из application.yml
     ▼
ManagedPool.apply(desired, reason)                 pool/ManagedPool.java:70  (synchronized)
     │
     ├─ normalize()                                pool/HikariSettings.java:69
     │    вне диапазона → InvalidSettingsException → REJECTED, пул не тронут
     │    мягкие проблемы → warning + починка
     │
     ├─ sameTarget() == false (jdbcUrl/креды/имя) → create() + drain()   → RECREATED
     │
     ├─ diff() пуст                                → ничего не делать     → UNCHANGED
     │
     └─ applyOnLivePool()                          pool/ManagedPool.java:112
          максимум/минимум/таймауты через HikariConfigMXBean             → RESIZED
```

Всё это выполняется в одном потоке — `etcd-config-watch` (платформенный daemon-поток,
`EtcdPoolConfigSource.java:82`). Метод `apply()` синхронизирован, поэтому два события,
пришедшие подряд, никогда не применятся одновременно.

## Снимок + watch

Периодического опроса etcd нет вообще: конфигурация приезжает событиями. При старте
`EtcdPoolConfigSource:119-141`:

```java
long revision = snapshot(c);   // get по префиксу, isPrefix=true
watch(c, revision + 1);       // подписка со следующей ревизии
```

Ключевой момент — `revision + 1`. Ревизия берётся из ответа снимка, поэтому если ключ
поменялся в промежутке между `get` и `watch`, это изменение придёт первым же событием.
Окна «потерянного обновления» не существует.

`snapshot()` (`:160`) полностью перезатирает `keys` свежим содержимым, ставит `connected=true`,
чистит `lastError` и сразу вызывает `apply("etcd-снапшот@rev")`. То есть после любого
переподключения конфиг применяется заново целиком — без дырок и без «частичного» состояния.

`onEvent()` (`:213`) накапливает батч: `PUT` → `keys.put`, `DELETE` → `keys.remove`. Удаление
ключа — не ошибка, а «вернуться к дефолту». Пустые батчи (от etcd прилетают как прогресс-сообщения)
игнорируются, чтобы не дёргать apply.

## Обрывы: три уровня

| что случилось | поведение |
|---|---|
| compaction, рестарт etcd, `onCompleted` | `finished.countDown()` → `watch()` возвращается → новый снимок + новый watch |
| etcd недоступен, таймаут `get` (`call-timeout`, 5с) | `connected=false`, `lastError` заполняется, сон с backoff'ом |
| etcd мигнул на пару секунд | backoff сбрасывается на начальное значение (1с) сразу после первого успешного прохода цикла (`:127`) |

Backoff растёт вдвое от `retry-initial-backoff` (1с) до `retry-max-backoff` (30с). Клиент при
этом не пересоздаётся: `client()` (`:143`) ленивый и под `synchronized`, один `Client` на весь
жизненный цикл. Пересоздавать его пришлось бы ещё и синхронизировать с in-flight watch.

Итог: обрыв связи с etcd не влияет на пул. Он продолжает работать на последнем применённом
конфиге и докатывает изменения, когда etcd вернётся. Сервис при этом не падает и на старте.

## Порядок сеттеров при изменении на живом пуле

`applyOnLivePool()` (`ManagedPool.java:112`) — единственное место, где меняется живой пул.

```java
if (shrinking) {
    setMinimumIdle(mx, ...);   // сначала меньший
    setMaximumPoolSize(mx, ...);
} else {
    setMaximumPoolSize(mx, ...);
    setMinimumIdle(mx, ...);
}
```

**Почему порядок именно такой.** `HikariConfigMXBean` проверяет `minimumIdle <= maximumPoolSize`
в обоих сеттерах. При уменьшении пула со значениями `max=30, minIdle=30` сначала нужно опустить
`minimumIdle` — иначе `setMaximumPoolSize(3)` упал бы с `minimumIdle > maximumPoolSize`, и весь
конфиг (включая таймауты, которые вполне валидны) не применился бы.

Дальше таймауты применяются через `setQuietly` (`:161`): каждый сеттер обёрнут в try/catch,
значение, которое не удалось применить, не роняет остальные — в лог уходит ошибка по этому
одному полю, пул продолжает работать.

**Почему после shrink нужен `softEvictConnections`.** Новый лимит применяется сразу, а вот
лишние idle-соединения живут до следующего прохода HouseKeeper (~30 секунд). В этот промежуток
`/api/pool` показывал бы `total=30` при `maximumPoolSize=3`. `softEvictConnections()` (`:140`)
вытесняет всё сверх `minimumIdle` немедленно, и `total` становится равен `minimumIdle` в тот же
момент.

## eagerFill: почему соединения надо одновременно держать

`eagerFill()` (`ManagedPool.java:179`) — демо-помощь: после увеличения пула сразу открыть нужное
число коннектов, иначе рост виден только под нагрузкой или через ~30 секунд HouseKeeper.

Тонкость, на которой легко ошибиться: соединения нужно **одновременно удерживать**. Если взять
коннект и сразу вернуть, HikariCP отдаст то же самое idle-соединение, и `total` не вырастет.
Поэтому все соединения набираются в список `held` и только в `finally` возвращаются:

```java
while (runtime().total() < target.maximumPoolSize() && System.nanoTime() < deadline) {
    held.add(ds.getConnection());
}
// ... возврат всех разом
```

Дедлайн — 2 секунды (`EAGER_FILL_TIMEOUT`). Он нужен, чтобы насыщенный нагрузкой пул не
подержал watch-поток: `pool.db.eager-fill-on-resize=false` (в `application.yml` это
`POOL_EAGER_FILL`) полностью отключает добивку, и коннекты добираются по мере надобности.

## Пересоздание пула и дренаж

`jdbcUrl`, `username`, `password`, `poolName` через MBean не меняются — определяется это сравнением
`sameTarget()` (`HikariSettings.java:115`). Тогда `create()` (`:212`) собирает новый
`HikariDataSource`, переключает `AtomicReference` на него и закрывает старый.

Проблема: `HikariDataSource.close()` закрывает и активные соединения, запросы в полёте падают
с `08006`. Поэтому `drain()` (`:266`) сначала вытесняет idle через `softEvictConnections()`,
потом ждёт до `pool.db.drain-on-recreate-timeout` (3с), пока активные соединения доработают:

```java
pool.softEvictConnections();
while (pool.getActiveConnections() > 0 && System.nanoTime() < deadline) {
    Thread.sleep(20);
}
```

Что не доработало за 3с — закрывается принудительно, клиенты получат ошибку. Это осознанный
компромисс: повесить переключение навсегда из-за одного долгого запроса хуже.

Переключение происходит через `AtomicReference`, поэтому `JdbcTemplate`, который держит ссылку
на `ManagedPool` (а не на конкретный `HikariDataSource`), продолжает работать без изменений.
Вот ради этого `ManagedPool` и реализует `DataSource` сам, а не отдаёт наружу `HikariDataSource`.

## Валидация

Два уровня, оба в `HikariSettings.normalize()` (`:69`).

**Жёсткий** — `InvalidSettingsException`, конфиг отклоняется целиком, пул остаётся на последнем
рабочем значении:

| поле | диапазон |
|---|---|
| `maximumPoolSize` | 1..200 |
| `minimumIdle` | 0..200 |
| `connectionTimeoutMs` | 250..600000 |
| `idleTimeoutMs` | 0 (выключен) или 10000..3600000 |
| `maxLifetimeMs` | 0 (выключен) или 30000..3600000 |
| `validationTimeoutMs` | 250..60000 |
| `leakDetectionThresholdMs` | 0 (выключен) или 2000..600000 |
| `jdbcUrl` | обязан начинаться с `jdbc:` |
| `username`, `poolName` | обязательны |
| `password` | обязателен, но может быть пустой строкой (вход без пароля) |

**Мягкий** — warning в лог, значение чинится, конфиг применяется:

- `minimumIdle > maximumPoolSize` → `minimumIdle` понижается до `maximumPoolSize`
  (важно: `make set-min-idle SIZE=8`, а потом `make set-size SIZE=3` не уронит конфиг);
- `idleTimeout > 0 && minimumIdle == maximumPoolSize` → предупреждение, что таймаут не применится;
- `leakDetectionThresholdMs >= connectionTimeoutMs` → предупреждение о ложных срабатываниях.

Почему одно и то же нарушение трактуется по-разному: `minimumIdle > max` — это следствие
нормализации (не задан `minimumIdle`, а `maximumPoolSize` уменьшили), чинить безопасно. А
`maximumPoolSize=0` — осмысленное «хочу ноль коннектов», которое технически невозможно, и
молча подменять его на 1 было бы враньём.

Предупреждения показываются только когда конфиг реально изменился (`ManagedPool.java:98`),
иначе они сыпались бы на каждый `put` того же значения.

## Плохое значение vs плохой конфиг

Два разных уровня деградации, и это осознанное решение:

| ситуация | что происходит |
|---|---|
| значение не число (`banana`) | игнорируется **только этот ключ**, в настройках остаётся `null` → возьмётся дефолт; остальные ключи применяются; причина попадает в `etcd.problems` в `/api/pool` |
| значение вне диапазона (`0`) | отклоняется **весь конфиг целиком**, потому что изменить именно это значение нельзя, а оставить его как есть — значит нарушить инвариант |

Результат: опечатка в одном ключе не блокирует правку соседних, но бессмысленное значение
размеров не применяется молча. Событие `put` с тем же значением, которое уже применено,
проходит как `UNCHANGED` — ни лога, ни apply.

## Исходы применения

`ManagedPool.Outcome` (`:381`) — что в итоге произошло:

| outcome | когда | видно |
|---|---|---|
| `CREATED` | первый пул в процессе | `HikariCP 'pool-service' создан: ...` |
| `RESIZED` | настройки изменились, пул тот же | `обновлён на лету: maximumPoolSize: 10 -> 25` + строка eager fill при росте |
| `RECREATED` | сменилась цель (jdbcUrl/креды/имя) | `пересоздан (сменилась цель: jdbcUrl/креды/имя)`, перед этим строки про дренаж |
| `UNCHANGED` | diff пуст | ничего, только debug |
| `REJECTED` | `normalize()` бросил исключение | `конфиг отклонён (...), остаёмся на предыдущих значениях: ...` + причина в `etcd.lastError` |

Результат последнего применения виден в `/api/pool` → `etcd.lastOutcome`, счётчик — в
`etcd.applyCount`, счётчики ресайзов и пересозданий — в `pool.resizeCount` / `pool.recreationCount`.

## Неочевидное про первый apply

На старте `lastOutcome` почти всегда `RESIZED`, хотя никто ничего руками не менял. Это нормально:
локальный дефолт `connectionTimeoutMs` в `application.yml` — 30000, а etcd-seed кладёт 3000.
Первое применение честно приводит пул к etcd-значению. Смотреть надо на `pool.lastChangeReason`
— он покажет, откуда пришло изменение (`etcd-снапшот@5`, `etcd@7 [maximumPoolSize=put]` и т.п.).
