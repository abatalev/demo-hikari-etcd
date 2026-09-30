# API: примеры ответов и разбор полей

Все примеры сняты с живого стенда (`make up`, дефолт 8 инстансов по 25 — бюджет по 100 на сервис,
нагрузчик с 4 потоками), не выдуманы.

Общее для всех ответов: `spring.jackson.default-property-inclusion: non_null` в `application.yml:8`
выбрасывает `null`-поля. Поэтому отсутствие ключа означает «нет значения», а не «значение null».

## `GET /api/pool`

Главный эндпоинт: пул + применённый конфиг + сессии postgres + состояние etcd.

```json
{
  "pool": {
    "poolName": "service-a-group-1-1",
    "generation": 1,
    "closed": false,
    "total": 25,
    "active": 4,
    "idle": 21,
    "threadsAwaitingConnection": 0,
    "maximumPoolSize": 25,
    "minimumIdle": 25,
    "unreleasedConnections": 0,
    "drainingGeneration": 0,
    "drainingTotal": 0,
    "createdAtEpochMs": 1790588803069,
    "lastChangeEpochMs": 1790588803885,
    "lastChangeReason": "etcd-снапшот@5",
    "resizeCount": 0,
    "recreationCount": 0
  },
  "config": {
    "jdbcUrl": "jdbc:postgresql://postgres:5432/demo",
    "username": "app",
    "password": "***",
    "poolName": "service-a-group-1-1",
    "maximumPoolSize": 25,
    "minimumIdle": 25,
    "connectionTimeoutMs": 3000,
    "idleTimeoutMs": 600000,
    "maxLifetimeMs": 1800000,
    "validationTimeoutMs": 5000,
    "leakDetectionThresholdMs": 0
  },
  "postgres": { "sessions": 25, "active": 4, "idle": 21 },
  "etcd": {
    "enabled": true,
    "connected": true,
    "endpoints": ["http://etcd:2379"],
    "path": "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/",
    "keys": { "connectionTimeoutMs": "3000", "maximumPoolSize": "25" },
    "problems": {},
    "revision": 5,
    "lastEventEpochMs": 0,
    "applyCount": 1,
    "lastOutcome": "CREATED"
  }
}
```

### `pool` — срез `HikariPoolMXBean` и `HikariConfigMXBean`

| поле | что это |
|---|---|
| `generation` | сколько раз пул создавался за жизнь процесса. `1` = ни разу не пересоздавался, `2` = было пересоздание из-за смены `jdbcUrl`/кредов |
| `closed` | `false` у живого пула; `true`, когда пула нет (старт до первого конфига, инстанс без доли, холодный флот `R=0`) — `ManagedPool.close()` обнуляет ссылку, дальше любой `getConnection()` бросит `IllegalStateException` |
| `total` | физически открытых соединений обоих поколений (текущего и освобождаемого). **Не равно `maximumPoolSize`**: растёт до него по мере надобности, если `eager-fill-on-resize=false` |
| `active` | сейчас занято запросами. При `total` = 10 и `active` = 4 четыре потока нагрузчика держат коннекты в `pg_sleep` |
| `idle` | свободные соединения. `total = active + idle` |
| `threadsAwaitingConnection` | сколько потоков стоит в очереди за коннектом. Ненулевое значение — единственный признак перегруза; при `connectionTimeoutMs` эти потоки начнут падать с timeout |
| `maximumPoolSize`, `minimumIdle` | значения, реально применённые в пуле (через MBean), а не то, что лежит в etcd |
| `unreleasedConnections` | сколько соединений пул держит **сверх `maximumPoolSize`**: `max(0, total − maximumPoolSize)`. Ненулевое значение — идёт дренаж после сжатия (или снятия конфигурации). Это ровно то число, которое инстанс публикует в etcd как `unreleasedConnections` и по которому провижёр решает, сколько места можно отдать растущим. Ноль при `total` **меньше** `maximumPoolSize` — это не долг: пул просто не добит, место под него есть |
| `drainingGeneration` | поколение пула, ушедшего в дренаж; `0`, если пересоздания не было. Пока оно ненулевое, `total` включает соединения и старого поколения — `total` не падает в ноль в момент `RECREATED` |
| `drainingTotal` | соединения освобождаемого поколения, всё ещё открытые. Ненулевое при `drainingGeneration > 0` означает, что старый пул ещё не допранажирован: запросы в полёте на нём ещё есть |
| `lastChangeReason` | откуда пришло последнее изменение: `startup`, `etcd-снапшот@5`, `etcd@7 [maximumPoolSize=put]`. Главное поле для разбора «кто это сделал» |
| `resizeCount`, `recreationCount` | счётчики apply'ев: ресайз против полного пересоздания |

### `config` — эффективный применённый конфиг

Это результат `etcd → parse → resolve(локальные дефолты) → normalize`, то есть то, с чем пул
работает прямо сейчас. Пароль замаскирован (`redacted()` в `HikariSettings.java:148`).

Расхождение с `etcd.keys` — норма: в etcd может лежать только `maximumPoolSize`, а здесь все поля,
потому что недостающие взяты из `pool.db.*`. Если `etcd.problems` не пуст, часть значений в
`config` — дефолты, а не то, что вы положили в etcd.

### `postgres` — сессии по `application_name`

`application_name` ставится в `ManagedPool.create()` через `HikariConfig.addDataSourceProperty`
(`ManagedPool.java:228`), поэтому считаются сессии именно нашего пула, а не всей базы.

**Поле может не прийти.** Если `idle = 0`, пул насыщен, и запрос за метаданными сам взял бы
последний свободный коннект — в этом случае вместо чисел придёт:

```json
{ "error": "пропущено: свободных коннектов в пуле нет (active=3)" }
```

Это осознанное поведение: интроспекция не должна усугублять очередь. Смотрите в этом случае
`make sessions` — это `psql` напрямую, мимо пула. Свой таймаут в 2с на этот запрос стоит на
`metaJdbcTemplate` (`PoolBeansConfiguration.java:44`), чтобы он не висел на насыщенном пуле.

### `etcd` — состояние источника конфигурации

| поле | что это |
|---|---|
| `enabled` | `false`, если `ETCD_ENABLED=false`: etcd не опрашивается вообще, пула нет (локальный максимум жёстко 0) |
| `connected` | `running && connected`. **`false` означает, что watch не работает прямо сейчас** — и это единственный признак, по которому отличают «etcd недоступен» от «событий просто не было» |
| `path` | путь конфигурации этого инстанса: `{root}/services/{service}/groups/{group}/instances/{instance}/hikari/`. **Отсутствует при `enabled: false`** — путь не собирается, когда источник выключен (non_null) |
| `keys` | снимок того, что сервис видит в etcd. Не пусто, а частично — например, в примере выше только два ключа, потому что config-provisioner кладёт `maximumPoolSize` и `connectionTimeoutMs` |
| `problems` | ключи, значение которых не удалось прочитать: `{"maximumPoolSize": "'banana' — ожидалось целое число, взято значение по умолчанию"}`. Пустой объект `{}` — всё в порядке |
| `revision` | ревизия etcd, на которой сервис находится. Растёт на каждом изменении; **не меняется — значит watch жив и просто ничего не происходит** |
| `lastEventEpochMs` | время последнего события watch. `0` — событий не было, применён только снимок при старте. Это не ошибка, но при `connected: false` означает «данные могут быть протухшими» |
| `applyCount` | сколько раз применялся конфиг. Растёт и на ресайзе, и на отклонённом конфиге |
| `lastOutcome` | итог последнего apply: `CREATED`, `RESIZED`, `RECREATED`, `CLOSED` (целевой размер 0 — пул снят с дренажом), `UNCHANGED`, `REJECTED` |
| `lastError` | появляется только при проблеме: ошибка etcd, compaction, либо список причин от `REJECTED` |
| `notReadyReason` | почему закрыт гейт трафика: «конфигурация не получена: в пути … нет распознанных ключей» или «…: etcd недоступен (…)». Отдельное поле, не перекрывает `lastError`; отсутствует, когда источник выключен или трафик открыт |

`lastError` — поле, которое стоит мониторить в проде. Оно заполняется в трёх случаях: сломался
etcd-клиент, прилетел `REJECTED`-конфиг, не удалось применить конфиг целиком.

**Про узел регистрации.** Инстанс при старте создаёт в etcd узел со своим именем
(`.../instances/{instance}`), живущий на аренде и исчезающий при остановке. В `keys` и `path`
этот узел **не появляется**: ему нет места в API — он лежит вне префикса `hikari/` и конфиг-воркер
его не читает. Регистрацию смотрят по ключам etcd: `make registrations` (см.
[operations.md](operations.md#регистрация-инстансов)).

**Про `lastOutcome: CREATED` на старте.** В примере выше пул появился из первого же снимка. Это
нормально: локальный максимум пула жёстко 0 — до конфигурации пула нет, а первый снимок от
провижора приносит долю бюджета, на которой пул создаётся. Ориентироваться надо на
`pool.lastChangeReason`, он показывает источник изменения.

Проверяется так: запустите сервис локально с `ETCD_ENABLED=false` (см.
[operations.md](operations.md#режим-без-etcd)) — пула не будет вовсе: в `/api/pool` `pool.closed=true`,
`pool.maximumPoolSize=0`, а `config` отсутствует целиком, `etcd.applyCount=0`. С etcd пул создаётся
из первого конфига, и `lastOutcome` будет `CREATED`.

## Поведение при `ETCD_ENABLED=false`

Полезно знать, что выглядит «выключенный etcd» в API:

```json
{
  "enabled": false,
  "connected": false,
  "endpoints": ["http://localhost:2379"],
  "keys": {},
  "problems": {},
  "revision": 0,
  "lastEventEpochMs": 0,
  "applyCount": 0
}
```

`connected: false` здесь **не авария** — так и должно быть. Отличать надо по `enabled`: если
`enabled: false`, etcd не опрашивался и применяться нечего (`applyCount: 0`). Если же
`enabled: true` при `connected: false` — вот это уже проблема, смотрите `lastError`.
Поле `lastOutcome` отсутствует целиком: apply не происходил, а `null`-поля выбрасываются.
`path` и `notReadyReason` тоже отсутствуют: при выключенном источнике путь не собирается.
Пула нет тоже: локальный максимум жёстко 0, поэтому в `/api/pool` `pool.closed=true`,
`pool.maximumPoolSize=0`, а `config` отсутствует целиком.

## `GET /api/config`

Ровно объект `etcd` из `/api/pool`, без обёртки. Часто удобнее для наблюдения: один компактный
ответ вместо всей простыни.

```json
{
  "enabled": true,
  "connected": true,
  "endpoints": ["http://etcd:2379"],
  "path": "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/",
  "keys": { "connectionTimeoutMs": "3000", "maximumPoolSize": "25" },
  "problems": {},
  "revision": 5,
  "lastEventEpochMs": 0,
  "applyCount": 1,
  "lastOutcome": "CREATED"
}
```

## `GET /api/work?ms=50`

Нагрузочная точка: взять коннект из пула, сделать запрос, поспать в БД `ms` миллисекунд.
На ней видно, как `maximumPoolSize` превращается в очередь и в таймауты.

```json
{
  "ok": true,
  "durationMs": 50,
  "dbMs": 50,
  "queueWaitMs": 0,
  "rows": 2000,
  "pool": {
    "maximumPoolSize": 25, "minimumIdle": 25,
    "total": 25, "active": 4, "idle": 21,
    "threadsAwaitingConnection": 0
  }
}
```

| поле | что это |
|---|---|
| `ok` | `false` и HTTP 503, если не удалось взять коннект. Тело — с `error` вида `SQLTransientConnectionException: ... connection is not available, request timed out after 3000ms` |
| `durationMs` | всё время с точки входа в эндпоинт |
| `dbMs` | из них `pg_sleep`. Приблизительно равно `ms` |
| `queueWaitMs` | `durationMs - dbMs`, то есть сколько запрос прождал свободный коннект. **Главная метрика:** `queueWaitMs` близко к нулю — пул справляется, растёт — упирается в размер |
| `rows` | `count(*)` из `demo_items` (2000 строк). Отражает размер таблицы, а не нагрузку |
| `pool` | снимок пула в момент ответа. Именно этот снимок нагрузчик раз в `REPORT_MS` берёт из `/api/pool`, а не из этого ответа |

**503 от гейта конфигурации (без входа в метод).** Пока инстанс не получил конфигурацию из etcd,
`/api/work` отвечает отдельным фильтром `TrafficGateFilter` до контроллера:

```json
{ "error": "конфигурация не получена: в пути /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/ нет распознанных ключей", "ok": false }
```

Отличается от 503 перегруза тем, что `pool`, `durationMs`, `dbMs` отсутствуют целиком — запрос
не дошёл до пула. `/api/pool` и `/api/config` при этом работают: неготовый инстанс обязан быть
наблюдаемым.

`ms` ограничен диапазоном 0..5000, за пределами — 400. При `ms=0` запрос не удерживает коннект,
это способ измерить чистое время получения коннекта из пула.

Значения `ms`, дающие видимый эффект на стенде: 25 (лёгкая постоянная нагрузка, 4 потока),
200 (перегрузка при маленьком пуле), 1000 и выше (легко увидеть очередь).

## `GET /actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`

Health разбит на две группы с осознанной границей:

- `readiness` — `readinessState,db,poolEtcd`: готова ли система принимать трафик. Пока гейт
  конфигурации закрыт, `poolEtcd` отдаёт `DOWN` с причиной в деталях, и группа в целом `DOWN`.
  Комpose-healthcheck и `make up` смотрят именно в неё.
- `liveness` — только `ping`: процесс жив независимо от etcd и базы. Обрыв etcd не валит
  liveness и не роняет под в k8s.

Готовый инстанс:

```json
{ "status": "UP", "components": { "db": { "status": "UP" }, "poolEtcd": { "status": "UP", "details": { "config-source": "конфигурация получена", "path": "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/" } }, "readinessState": { "status": "UP" } } }
```

Не готовый (etcd ещё не отдал конфигурацию):

```json
{ "status": "DOWN", "components": { "poolEtcd": { "status": "DOWN", "details": { "config-source": "трафик закрыт: конфигурация не получена", "path": "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/", "reason": "конфигурация не получена: в пути ... нет распознанных ключей" } } } }
```

Связь с etcd в деталях смотреть в `/api/config` → `connected` и `lastError`.
