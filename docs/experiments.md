# Эксперименты: что реально получалось на стенде

> **Примечание.** Эти замеры сняты на одноинстансном стенде до разделения путей
> (`/config/pool-service/hikari/…`, один сервис): после `multi-instance-key-path` на стенде 8
> инстансов с путями `/config/services/{service}/groups/{group}/instances/{instance}/hikari/…`, команды выше принимают
> `I=`/`S=`/`G=` (см. README). Перцентили, форма сигнатуры (p50 близко к времени работы, p95/хвост —
> очередь) и порядки величин остаются релевантными: та же HikariCP, те же настройки.

Все цифры сняты с живого стенда (`make up`, PostgreSQL 16, etcd 3.6.15, ноутбук без тюнинга).
Стенд: `loadgen` с 4 постоянными потоками по 25мс, если не указано иное. Пул = 10 по умолчанию,
`connectionTimeoutMs` = 3000 (его кладёт config-provisioner), `eager-fill-on-resize=true`.

## Рост и сжатие пула на живом сервисе

```bash
make set-size SIZE=30
```

Через ~1с:

```json
{"maximumPoolSize": 30, "total": 30, "active": 4, "idle": 26,
 "threadsAwaitingConnection": 0, "resizeCount": 2, "recreationCount": 0}
postgres: {"sessions": 30, "active": 5, "idle": 25}
```

`total` стал ровно 30, а не «по мере надобности», потому что включён `eager-fill-on-resize`.
`recreationCount` остался 0 — пул не пересоздавался, это правка через MBean. Запросы в полёте
(4 потока в `pg_sleep`) не прервались.

Сжатие:

```bash
make set-size SIZE=5
```

| момент | maximumPoolSize | total | idle |
|---|---|---|---|
| до | 30 | 30 | 26 |
| сразу после | 5 | 5 | 1 |
| +1s … +31s | 5 | 5 | 1 |

Мгновенно, без ожидания HouseKeeper. Это заслуга `softEvictConnections()` в
`ManagedPool.applyOnLivePool()`: без него лишние idle-соединения висели бы ещё ~30 секунд,
и `total` показывал бы 30 при `maximumPoolSize=5`.

## Перегрузка: 32 потока × 200мс при пуле в 3

```bash
make set-size SIZE=3
WORKERS=32 WORK_MS=200 DURATION_S=20 make stress
```

```
[t=  4s] rps=17 p50=1068 p95=3043 max=3045 inflight=32 ok=55  err=16 | pool: max=3 total=3 active=3 idle=0 waiting=34
[t=  8s] rps=15 p50=1142 p95=3043 max=4722 inflight=32 ok=115 err=18 | pool: max=3 total=3 active=3 idle=0 waiting=33
[t= 12s] rps=15 p50=1276 p95=3447 max=5698 inflight=32 ok=172 err=23 | pool: max=3 total=3 active=3 idle=0 waiting=34
[t= 16s] rps=17 p50=1372 p95=3234 max=5698 inflight=32 ok=232 err=35 | pool: max=3 total=3 active=3 idle=0 waiting=33
итого: запросов=368 ok=319 err=49 p50=1397ms p95=3137ms max=5698ms
```

Читается однозначно: 3 коннекта × (1000/200мс) = ~15 rps — ровно то, что показывает нагрузчик.
`waiting=33-34` значит, что почти все 32 потока стоят в очереди. `err` растёт монотонно (16 → 35):
это запросы, отвалившиеся по `connectionTimeoutMs=3000`, они не повторяются и портят средние
(`p50=1397ms`, `max=5698ms` — хвост из ожидания плюс таймауты).

Показательно, что `pg: n/a ("пропущено: свободных коннектов в пуле нет (active=3)")` — интроспекция
сессий не прошла именно потому, что пул насыщен. Это ровно то поведение, которое задумано:
запрос за метаданными не должен занимать последний свободный коннект. Смотреть сессии в этом
состоянии надо через `make sessions` (psql напрямую).

## Расширение под нагрузкой: 3 → 30 на ходу

Те же 32 потока × 200мс, на 12-й секунде — `maximumPoolSize = 30`:

```
[t=  4s] rps=14  p50=1088 p95=3171 max=3430 inflight=32 ok=55  err=4  | pool: max=3  total=3  active=3  idle=0 waiting=34
[t=  8s] rps=18  p50=1209 p95=3078 max=3430 inflight=32 ok=115 err=17 | pool: max=3  total=3  active=3  idle=0 waiting=33
[t= 12s] rps=16  p50=1213 p95=3100 max=3430 inflight=32 ok=173 err=26 | pool: max=30 total=9  active=9  idle=0 waiting=29
[t= 16s] rps=126 p50=239  p95=3001 max=3430 inflight=32 ok=680 err=26 | pool: max=30 total=30 active=30 idle=0 waiting=6
[t= 20s] rps=139 p50=231  p95=2462 max=3430 inflight=32 ok=1239 err=26 | pool: max=30 total=30 active=30 idle=0 waiting=6
[t= 24s] rps=139 p50=229  p95=1235 max=3430 inflight=32 ok=1799 err=26 | pool: max=30 total=30 active=30 idle=0 waiting=6
[t= 28s] rps=139 p50=228  p95=1209 max=3430 inflight=32 ok=2358 err=26 | pool: max=30 total=30 active=30 idle=0 waiting=6
итого: запросов=2971 ok=2945 err=26 p50=228ms p95=1183ms max=3430ms
```

Ключевое: `err` застывает на 26 — после расширения новых таймаутов нет. Суммарно 2971 запросов
против 368 в том же окне на пуле в 3, то есть **пропускная способность выросла в 8 раз без
перезапуска сервиса**.

t=12s — переходный момент: лимит 30 уже применён (`max=30`), но соединения ещё создаются
(`total=9`), потому что 32 запроса в полёте выели все свободные. `waiting` падает плавно.
К `t=16s` картина стабилизировалась: `waiting=6` — это 32 воркера минус 30 занятых коннектов,
ровно как и должно быть при `pg_sleep(200ms)`.

Так видно, зачем в стенде `eager-fill-on-resize`: без него на t=12s было бы `total=3`, и рост
заметился бы только под нагрузкой, а на холостом пуле — через ~30с, после HouseKeeper.

## Плохой конфиг

### `maximumPoolSize = 0` — отказ целиком

```
hikari - [etcd@9 [maximumPoolSize=put]] конфиг отклонён (maximumPoolSize=0 вне диапазона [1..200]),
         остаёмся на предыдущих значениях: HikariSettings[... maximumPoolSize=30, minimumIdle=30 ...]
```

```json
{"pool": {"maximumPoolSize": 30, "total": 30}, "etcd": {"lastOutcome": "REJECTED"}}
```

Пул остался на 30, в `etcd.lastError` — `rejected: maximumPoolSize=0 вне диапазона [1..200]`.
Сервис жив, ни один запрос не упал.

### `maximumPoolSize = banana` — игнорируется только этот ключ

Подряд положены `maximumPoolSize=banana` и `connectionTimeoutMs=1500`:

```
etcd.EtcdPoolConfigSource - [etcd@10 ...] ключ maximumPoolSize проигнорирован: 'banana' — ожидалось целое число, взято значение по умолчанию
hikari - [etcd@10 ...] пул 'pool-service' обновлён на лету: maximumPoolSize: 30 -> 10, minimumIdle: 30 -> 10
```

```json
{"pool": {"maximumPoolSize": 10},
 "config": {"connectionTimeoutMs": 1500},
 "etcd": {"problems": {"maximumPoolSize": "'banana' — ожидалось целое число, взято значение по умолчанию"},
           "lastOutcome": "RESIZED"}}
```

Соседний ключ применился (`connectionTimeoutMs` 3000 → 1500), битый ключ откатился на дефолт 10.
`lastOutcome` при этом `RESIZED`, а не `REJECTED` — конфиг в целом рабочий.

**Особенность, о которой стоит знать:** `etcd.problems` не очищается, когда значение чинят.
Проверено: после `put maximumPoolSize=12` (нормальное число) поле продолжало показывать
старую запись про `banana`. Смотрите на `config` / `pool.maximumPoolSize`, чтобы понять текущее
состояние, а `problems` — как журнал: «что-то когда-то было плохо». Обнуляется только рестартом.

### Опечатка `maxPoolSize = 8`

```
WARN ... в etcd есть неизвестный ключ 'maxPoolSize' (префикс '/config/pool-service/hikari/') — он игнорируется
```

`maximumPoolSize` остался 10, ключ `maxPoolSize` виден в `etcd.keys` рядом с настоящим —
удобно заметить при разборе. Предупреждение пишется один раз на ключ, не на каждый put.

### Удаление ключа

`etcdctl del maximumPoolSize` → пул вернулся к 10 (локальный дефолт), `revision` выросла до 13.
`DELETE` в etcd равнозначен «вернуться к дефолту», а не «запретить ключ».

## Смена цели: `jdbcUrl` → пересоздание с дренажем

```bash
make set-size SIZE=10   # вернуть размер
docker compose run --rm --no-deps etcdctl put "$PREFIX/jdbcUrl" \
  "jdbc:postgresql://postgres:5432/demo?connectTimeout=5"
```

```
hikari - [etcd@15 [jdbcUrl=put]] старый пул 'pool-service' закрывается: ждём 4 активных соединений (до PT3S)
hikari - [etcd@15 [jdbcUrl=put]] HikariCP 'pool-service' пересоздан (сменилась цель: jdbcUrl/креды/имя)
```

```json
{"pool": {"generation": 2, "recreationCount": 1, "maximumPoolSize": 10},
 "etcd": {"lastOutcome": "RECREATED", "keys": {"jdbcUrl": "jdbc:postgresql://postgres:5432/demo?connectTimeout=5"}}}
```

Дренаж занял ~70мс при 4 активных соединениях (4 потока нагрузчика были в `pg_sleep(25ms)`) —
все доработали естественно, до принудительного закрытия дело не дошло. `generation` 1 → 2 — единственный
параметр, по которому видно, что пул пересоздавался.

Заодно проверено, что `put` того же самого `jdbcUrl` даёт `UNCHANGED` и `generation` не растёт:
сравнение идёт по значению, а не по факту события.

## Падение и подъём etcd

```bash
docker compose stop etcd
```

| момент | `etcd.connected` | `revision` | `pool.total` | `lastError` |
|---|---|---|---|---|
| t+1s | `true` | 15 | 10 | `EtcdException: Connectio...` |
| t+3s | `false` | 15 | 10 | `TimeoutException` |
| t+20s | `false` | 15 | 10 | `TimeoutException` |

**Пул всё это время жил:** `total=10`, нагрузка продолжалась, ошибок от клиентов не было. Зависший
etcd не влияет на обслуживание — сервис работает на последнем применённом конфиге.

Первые ~2 секунды `connected` остаётся `true`, хотя etcd уже лежит: обрыв приходит асинхронно
(GOAWAY на существующем watch), а флаг сбрасывается только когда цикл дойдёт до новой попытки
снимка. Ориентироваться надо на `lastError`, если важна секундная точность.

Правку в etcd, пока он лежит, сделать нельзя — `etcdctl` падает на соединении. После подъёма:

```bash
docker compose start etcd
```

Watch восстановился за ~8 секунд (1с + 2с + 4с — три попытки с экспоненциальным backoff, до
первого успешного снимка), `connected=true`, `revision=15`, `lastOutcome=UNCHANGED`, `generation`
не изменился — пул всё это время продолжал жить, заново конфиг не применялся, потому что
применить было нечего.

## Сводка

| сценарий | результат |
|---|---|
| рост 10 → 30 | мгновенно, `total=30`, без пересоздания |
| сжатие 30 → 5 | мгновенно, `total=5`, лишние idle вытеснены сразу |
| 32×200мс, пул 3 | `waiting=33`, `p50=1397ms`, rps ~15, 49 ошибок по таймауту |
| пул 3 → 30 под нагрузкой | rps 15 → 139, `p50` 1397 → 228мс, `waiting` 34 → 6, ошибок больше не было |
| `maximumPoolSize=0` | `REJECTED`, пул на прежнем размере |
| `maximumPoolSize=banana` | только этот ключ откатился на дефолт, `connectionTimeoutMs` применился |
| опечатка `maxPoolSize` | warning, на конфиг не повлиял |
| удаление ключа | возврат к локальному дефолту |
| смена `jdbcUrl` | `RECREATED`, дренаж 70мс, `generation` 1 → 2 |
| etcd лежит 20с | пул жив, `connected=false`, автовосстановление за ~8с |

## Как повторить

```bash
make up
make load-logs                        # в отдельном терминале, для фона

make set-size SIZE=30                 # рост
make set-size SIZE=5                  # сжатие

make set-size SIZE=3
WORKERS=32 WORK_MS=200 DURATION_S=20 make stress

# то же самое, но с расширением на ходу (в соседнем терминале, на ~12-й секунде)
make set-size SIZE=30
WORKERS=32 WORK_MS=200 DURATION_S=32 make stress

# плохой конфиг
docker compose run --rm --no-deps etcdctl put "$PREFIX/maximumPoolSize" 0
docker compose run --rm --no-deps etcdctl put "$PREFIX/maximumPoolSize" banana

# etcd
docker compose stop etcd && sleep 15 && docker compose start etcd
```

После прогонов стенд возвращается в исходное состояние: `make clean && make up`. Ручные правки
через `docker compose run ... etcdctl put` перекрывают значения сида, `etcdctl del <ключ>`
возвращает дефолт. Ключи можно сбросить так:

```bash
P=/config/pool-service/hikari
for k in maximumPoolSize minimumIdle maxPoolSize jdbcUrl; do
  docker compose run --rm --no-deps etcdctl del "$P/$k"
done
docker compose run --rm --no-deps etcdctl put "$P/maximumPoolSize" 10
```
