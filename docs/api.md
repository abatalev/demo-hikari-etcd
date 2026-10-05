# API: примеры ответов и разбор полей

Все примеры сняты с живого стенда (`make up`, дефолт 8 инстансов по 25 — бюджет по 100 на сервис,
нагрузчик с 4 потоками), не выдуманы.

Общее для всех ответов: `spring.jackson.default-property-inclusion: non_null` в `application.yml:8`
выбрасывает `null`-поля. Поэтому отсутствие ключа означает «нет значения», а не «значение null».

Доменная точка у сервиса одна — нагрузочная `/api/work`. Состояния пула, применённой конфигурации и
источника конфигурации в HTTP-ответе нет намеренно: они наблюдаются в метриках, трассах и журнале
(разбор имён метрик — в разделе `GET /actuator/prometheus`), сводка по всему флоту — `make pool`,
путь конфигурации и число ключей в etcd печатает та же сводка.

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
| `pool` | снимок пула в момент ответа. Постоянное состояние пула снимается в метриках инстанса (`pool_*`), а нагрузчик этот снимок не читает |

**503 от гейта конфигурации (без входа в метод).** Пока инстанс не получил конфигурацию из etcd,
`/api/work` отвечает отдельным фильтром `TrafficGateFilter` до контроллера:

```json
{ "error": "конфигурация не получена: в пути /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/ нет распознанных ключей", "ok": false }
```

Отличается от 503 перегруза тем, что `pool`, `durationMs`, `dbMs` отсутствуют целиком — запрос
не дошёл до пула. Наблюдение при этом работает: неготовый инстанс обязан быть наблюдаемым —
`/actuator/prometheus` и `readiness` отдаются всегда, метрики `pool_traffic_gate_open` и
`pool_not_ready_reason` показывают закрытый гейт.

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

Связь с etcd в деталях `readiness` (`details.poolEtcd.reason`) и в журнале инстанса; признаки
источника — в метриках `pool_etcd_{enabled,connected,watch_active,revision,problems}`.

## `GET /actuator/prometheus`

Метрики инстанса в текстовом формате prometheus. Аутентификации нет, соединений из пула эндпоинт
**не берёт** — все величины берутся из снимка `runtime()`.

**Точка осталась, но сбор её больше не обслуживает.** Раньше сборщик опрашивал её у каждого инстанса
отдельно; теперь приложение отдаёт те же метрики отправкой в точку приёма сигналов, а сборщик берёт
их оттуда. Текстовая точка нужна человеку: `curl`, разбор вручную и сверка «а процесс сам видит то
же, что видно в наблюдении?». Имена метрик, наборы признаков и форма гистограмм на обоих путях
одинаковые, поэтому расхождение здесь и там — всегда баг, а не неоднозначность. Если увидите в
`prometheus/prometheus.yml` задачу сбора инстансов — это откат от штатного состояния.

Признаки на всех доменных метриках: `service`, `group`, `node` (значения `SERVICE_NAME`,
`ETCD_GROUP`, `ETCD_INSTANCE`). Метки `instance` нет — она зарезервирована сборщиком за адрес цели,
а у приложений адреса цели теперь нет вовсе: одна цель на весь флот, и различать инстансы надо по
`node`. У провизёра вместо них признак `replica` (`PROV_NAME`) и `service` у величин по сервису.
Тот же набор признаков приложение кладёт и в ресурс сигнала (для трасс и журналов, где общих тегов
метрик нет), поэтому по признакам инстанса находится и журнал, и трасса.

```text
# HELP pool_connections_open открытые соединения обоих поколений
# TYPE pool_connections_open gauge
pool_connections_open{group="group-1",node="service-a-group-1-1",service="service-a"} 25.0
# HELP pool_maximum_pool_size текущий потолок пула
# TYPE pool_maximum_pool_size gauge
pool_maximum_pool_size{group="group-1",node="service-a-group-1-1",service="service-a"} 25.0
# HELP pool_traffic_gate_open инстанс готов принимать трафик
# TYPE pool_traffic_gate_open gauge
pool_traffic_gate_open{group="group-1",node="service-a-group-1-1",service="service-a"} 1.0
# HELP hikaricp_connections_acquire_seconds …
# TYPE hikaricp_connections_acquire_seconds histogram
hikaricp_connections_acquire_seconds_count{…} 182
```

Полный список имён с описаниями — в
`openspec/changes/archive/2026-10-01-metrics-and-fleet-observability/design.md` (раздел «Имена
метрик»), он же остаётся каноническим при следующих изменениях. Кратко:

| группа | метрики |
|---|---|
| размер и состояние пула | `pool_maximum_pool_size`, `pool_minimum_idle`, `pool_generation`, `pool_closing`, `pool_connections_{open,busy,idle,awaiting}`, `pool_evictable_idle`, `pool_unreleased_shrink` |
| применённая конфигурация | `pool_config_{maximum_pool_size,minimum_idle,connection_timeout_ms}` — **только применённые величины**, без значений из etcd |
| состояние конфигурации | `pool_config_state{state=none\|applied\|rejected}`, `pool_config_{applied,rejected,unreadable}_total`, `pool_resize_{grow,shrink}_total`, `pool_recreate_total` |
| гейт и etcd | `pool_traffic_gate_open`, `pool_not_ready_reason{reason=none\|no_config_keys\|etcd_unavailable}`, `pool_etcd_{enabled,connected,watch_active,revision,problems}` |
| события пула (SPI HikariCP) | `hikaricp_connections_{acquire,usage,creation}_seconds` (гистограммы, границы заданы в коде), `hikaricp_connections_timeout_total` |

Что важно при чтении:

- **Значений конфигурации в метриках нет** (пароль в etcd лежит открытым текстом, а признаки-значения
  размножили бы кардинальность) — вместо них числовые гейджи применённых величин и состояние из
  закрытого набора.
- **`pool_etcd_problems` — журнал за весь процесс**, а не «что сломано сейчас»: непрочитанное значение
  в etcd остаётся в счётчике после исправления. Что именно не прочитано — в журнале инстанса.
- **Перцентили по событиям пула берутся из гистограмм**: `histogram_quantile(0.99, sum by (le)
  (rate(hikaricp_connections_acquire_seconds_bucket[$__rate_interval])))`. Границы заданы в коде
  (выдача 0.1мс…5с, удержание 1мс…5с, открытие 1мс…1с) и достроены Micrometer геометрически между
  ними, их около 80 на прибор. Если бы прибор остался summary, запрос по `_bucket` вернул бы пусто
  без ошибки.
- **События HikariCP и состояние пула — разные источники**: распределения ожидания/удержания/открытия
  приходят через SPI, а всё состояние — из `runtime()`. Штатный Micrometer-трекер HikariCP не
  используется: его гейджи привязаны к первому поколению пула и после пересоздания показывали бы ноль.
- **Ряды из закрытого набора всегда полные**: `pool_config_state` и `pool_not_ready_reason` публикуются
  по одному ряду на каждое значение признака, причём `reason="none"` = 1, когда причины нет.
