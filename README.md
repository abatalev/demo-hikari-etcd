# Hikari-пул, который настраивается через etcd

Прототип: размер пула соединений к PostgreSQL 16 меняется **на лету** правкой ключа в etcd.
Compose-стенд: PostgreSQL 16, etcd, сервис на Spring Boot 3 + HikariCP, нагрузчик и образ с etcdctl.

```
                    ┌──────────────────────────────┐
   etcdctl put ────► │ etcd                         │
   (правка размера) │ /config/pool-service/hikari/  │
                    │   maximumPoolSize = 25       │
                    └──────────────┬───────────────┘
                                   │ watch (jetcd, revision-aware)
                                   ▼
   ┌──────────────────────────────────────────────┐
   │ service (Spring Boot 3)                      │
   │                                              │
   │  EtcdPoolConfigSource                        │
   │    снимок префикса + watch                   │
   │    валидация, откат на последний рабочий     │
   │              │                               │
   │              ▼                               │
   │  ManagedPool (DataSource)                    │
   │    максимум/минимум/таймауты — через         │
   │    HikariConfigMXBean, без пересоздания       │
   │    jdbcUrl/креды — пересоздание с дренажем    │
   └───────────────────┬──────────────────────────┘
                       │ JDBC
                       ▼
              PostgreSQL 16 (application_name=pool-service)
                       ▲
                       │ HTTP /api/work
   ┌───────────────────┴──────────────────────────┐
   │ loadgen: N виртуальных потоков + репортер     │
   └──────────────────────────────────────────────┘
```

Как именно ключ из etcd доезжает до нового числа коннектов — [docs/mechanism.md](docs/mechanism.md).

## Быстрый старт

```bash
make up            # собрать и поднять всё, дождаться health, показать состояние пула
make load-logs     # смотреть, как нагрузка давит на пул
make set-size SIZE=25   # ← вот тут etcd меняет пул на живом сервисе
make load-logs     # видно: total=25, sessions=25
make down          # остановить; make clean — вместе с данными
```

Логи сервиса в момент правки:

```
[etcd@4 [maximumPoolSize=put]] eager fill: пул расширен с 10 до 25 коннектов одним изменением
[etcd@4 [maximumPoolSize=put]] пул 'pool-service' обновлён на лету: maximumPoolSize: 10 -> 25, minimumIdle: 10 -> 25
```

**Что нужно на машине:** Docker + `make` + `curl` + `python3` (и `make up` зовёт `python3 -m json.tool`
для вывода JSON). Maven нужен только для `make test` — образ сервиса собирается им внутри себя.
Локальный запуск без compose и режим `ETCD_ENABLED=false` — [docs/operations.md](docs/operations.md).

## Ключи конфигурации в etcd

Префикс `/config/pool-service/hikari/` (меняется через `ETCD_PREFIX`).

| ключ | пример | меняется на лету | комментарий |
|---|---|---|---|
| `maximumPoolSize` | `25` | да | главный рычаг, 1..200 |
| `minimumIdle` | `5` | да | не задан = держится равным `maximumPoolSize`; если меньше — в пуле появятся «спящие» коннекты, которые уходят по `idleTimeout` |
| `connectionTimeoutMs` | `3000` | да | сколько ждать свободный коннект |
| `idleTimeoutMs`, `maxLifetimeMs`, `validationTimeoutMs`, `leakDetectionThresholdMs` | | да | остальные таймауты HikariCP |
| `jdbcUrl`, `username`, `password`, `poolName` | | **нет** | только пересоздание пула |
| что угодно ещё | | | игнорируется с предупреждением (ловим опечатки) |

Диапазоны всех значений и что бывает за их нарушение — [docs/mechanism.md](docs/mechanism.md#валидация).

Локальные дефолты (`pool.db.*` в `application.yml`) — это фолбэк: ключ в etcd всегда выигрывает.
Если etcd недоступен, сервис не падает: он работает на последнем применённом конфиге (или на
локальных дефолтах, если etcd не отвечал ни разу) и докатывает конфиг, как только etcd вернётся.

## Команды

| команда | что делает |
|---|---|
| `make up` / `make down` / `make clean` | поднять / остановить / остановить вместе с томами |
| `make build` | пересобрать образы сервиса, нагрузчика и etcdctl |
| `make ps` | состояние контейнеров |
| `make config` | показать ключи в etcd |
| `make set-size SIZE=25` | `maximumPoolSize = 25` |
| `make set-min-idle SIZE=3` | зафиксировать `minimumIdle` |
| `make unset-min-idle` | снова follow за `maximumPoolSize` |
| `make set-conn-timeout SIZE=1000` | `connectionTimeoutMs` |
| `make pool` | что сейчас в пуле (+ конфиг, + сессии postgres) |
| `make work` | один запрос, как его делает нагрузчик |
| `make sessions` | сессии postgres по `application_name` (psql напрямую) |
| `make psql` | открыть консоль postgres |
| `make seed` | разложить стартовый конфиг в etcd (идемпотентно) |
| `make stress` | разовый прогон нагрузки (нужен `DURATION_S`, иначе не вернётся) |
| `make logs S=1` | логи сервиса вместе с etcd |
| `make load-logs` | логи нагрузчика |
| `make service-restart` | перезапустить только сервис |
| `make test` | юнит-тесты (нужен maven) |

## Что умеет и чего не умеет

**Меняется на живом пуле, без пересоздания** — через `HikariConfigMXBean`:
`maximumPoolSize`, `minimumIdle`, `connectionTimeoutMs`, `idleTimeoutMs`, `maxLifetimeMs`,
`validationTimeoutMs`, `leakDetectionThresholdMs`. Запросы в полёте не страдают.

**Требует пересоздания пула** — `jdbcUrl`, `username`, `password`, `poolName`: MBean этого не умеет.
Перед закрытием старого пула ждём до `drain-on-recreate-timeout` (3 c), пока доработают активные
соединения; что не доработает — закрывается принудительно, и клиенты получат ошибку.

Плохой конфиг не роняет сервис:

| что положили в etcd | что будет |
|---|---|
| `maximumPoolSize=0` | конфиг отклонён целиком, пул остался на прежнем размере, в лог ушла причина |
| `maximumPoolSize=banana` | только этот ключ проигнорирован (взят дефолт), соседние ключи применились, проблема видна в `/api/pool` → `etcd.problems` |
| `maxPoolSize=8` (опечатка) | предупреждение «неизвестный ключ», на конфиг не влияет |
| удалили ключ | значение возвращается к локальному дефолту |
| etcd лежит | сервис работает на последнем/дефолтном конфиге, watch докатывается с backoff'ом |

## Демо, которое стоит пройти

```bash
make up
make load-logs        # 4 потока × pg_sleep(25ms): total=10, pg sessions=10

make set-size SIZE=3      # сжатие
make load-logs            # total=3, sessions=3, очереди нет

# перегрузить: 32 потока × 200ms при пуле в 3
WORKERS=32 WORK_MS=200 DURATION_S=20 make stress
```

Последнее — единственный способ увидеть `threadsAwaitingConnection` и таймауты: при
`WORKERS=32`, `WORK_MS=200` и пуле в 3 коннекта запросы копятся в ожидании, а часть отваливается
по `connectionTimeoutMs` (loadgen посчитает их как `err`). **`DURATION_S` здесь обязателен:**
в `Makefile` переменная объявлена как `DURATION_S ?= 0`, поэтому подстановка
`$(or $(DURATION_S),20)` никогда не срабатывает, и без явного значения `make stress` не вернётся.

Как устроен нагрузчик (`loadgen/LoadGen.java`, один файл на JDK, без зависимостей):
`WORKERS` виртуальных потоков, `WORK_MS` — сколько держать коннект в `pg_sleep`,
`THINK_MS` — пауза между запросами, `REPORT_MS` — период отчёта, `DURATION_S=0` — крутится до
`docker compose stop loadgen`. Каждая строка отчёта: rps, p50/p95/max, inflight, ошибки +
состояние пула + сессии postgres.

## API

| эндпоинт | что делает |
|---|---|
| `GET /api/pool` | состояние пула, эффективный конфиг (пароль замаскирован), сессии postgres, статус etcd (revision, ключи, проблемы) |
| `GET /api/config` | только etcd-часть: ключи, revision, ошибки |
| `GET /api/work?ms=20` | взять коннект, сделать запрос, поспать в БД `ms` миллисекунд; в ответе `queueWaitMs` — сколько прождали коннект |
| `GET /actuator/health` | healthcheck |

С примерами ответа и разбором каждого поля — [docs/api.md](docs/api.md).
В `/api/pool` поле `postgres.sessions` — сколько сессий postgres держит пул
(`application_name=pool-service`, его ставит `HikariConfig.addDataProperty`).
Если пул занят полностью, этот запрос пропускается: он сам взял бы последний свободный
коннект и усугубил очередь. Тогда смотрите `make sessions` — это psql напрямую.

## Документация

| файл | о чём |
|---|---|
| [docs/mechanism.md](docs/mechanism.md) | путь `put` → resize: watch, валидация, порядок сеттеров, shrink, eager fill, recreate с дренажем |
| [docs/api.md](docs/api.md) | примеры ответов с разбором полей, неочевидные значения |
| [docs/experiments.md](docs/experiments.md) | что реально получалось при стенде, с цифрами |
| [docs/operations.md](docs/operations.md) | troubleshooting, переменные окружения, локальный запуск, ограничения для прода |
| [AGENTS.md](AGENTS.md) | инструкции для агентов: инварианты, которых нельзя нарушать, и подводные камни |

## Разработка: spec-driven через OpenSpec

Изменения проектируются до того, как пишется код. Спецификации и планы лежат в `openspec/`,
код — в `service/`.

```bash
openspec list --json                     # состояние спецификаций
openspec new change <kebab-case-name>    # завести изменение
openspec status --change <name> --json   # какие артефакты готовы
openspec validate <name>                 # проверить артефакты
```

Изменение проходит четыре артефакта: `proposal.md` (зачем) → `specs/<capability>/spec.md`
(контракт поведения, дельта к основной спеке) → `design.md` (как) → `tasks.md` (шаги).
В OpenCode это доступно как слэш-команды `/opsx-propose`, `/opsx-apply`, `/opsx-archive`,
`/opsx-explore`, `/opsx-sync`, `/opsx-update`.

Артефакты пишутся на русском; структурные заголовки и `SHALL`/`MUST` остаются английскими.
Планирование и реализация разнесены: `/opsx-propose` создаёт только артефакты, код трогается
после отдельного запроса на `/opsx-apply`. Подробности правил — в [AGENTS.md](AGENTS.md).

## Состав

```
service/    Spring Boot 3.5 + HikariCP + jetcd        (mvn test)
  pool/     ManagedPool, HikariSettings (валидация)    — сердце прототипа
  etcd/     EtcdPoolConfigSource (watch), EtcdKeys     — разбор ключей
  web/      PoolController
loadgen/    нагрузчик на голом JDK
etcd/       образ с etcdctl + скрипт сидинга конфига
db/init/    демо-таблица + view pool_sessions
openspec/   спецификации и изменения (config.yaml, specs/, changes/)
.opencode/  слэш-команды и скилы OpenSpec для OpenCode
```

Тестов 12: `HikariSettingsTest` (валидация, дефолты, diff) и `EtcdKeysTest` (разбор ключей) —
юнит-тесты без etcd и без БД.

## Решения, которые стоит знать

- **Пул создаётся один раз на весь процесс жизни приложения.** `ManagedPool` реализует
  `DataSource` и сам делегирует вызовы живому `HikariDataSource`, поэтому `JdbcTemplate` и
  менеджер транзакций не нужно пересоздавать — они следуют за подменой пула сами.
- **`minimumIdle` по умолчанию следует за `maximumPoolSize`** (как в HikariCP). Если бы он
  остался равным прежнему `maximumPoolSize`, уменьшение пула падало бы с `minimumIdle > maximumPoolSize`.
  Явное значение ключа в etcd — всегда выигрывает, при `minimumIdle > maximumPoolSize` значение
  аккуратно понижается до максимума с предупреждением.
- **Снимок + watch с `revision + 1`.** Разрыва между чтением и подпиской нет, а любой обрыв
  (compaction, рестарт etcd, сеть) приводит к новому снимку префикса — для префикса из ~10 ключей
  это проще и надёжнее, чем поддерживать инкрементальную дельту.
- **`eager-fill-on-resize=true`** (дефолт стенда) — после увеличения пула коннекты добиваются сразу,
  а не по мере надобности. Нужно для наглядности; в проде обычно `false`: лишние коннекты
  съедают лимит `max_connections` postgres (в стенде поднят до 200).
- **`initialization-fail-timeout=-1`** — сервис поднимается, даже если БД ещё не готова, пул
  дозаполнится сам. В проде лучше положительное значение, чтобы падать на старте.
- **Пароль в etcd** возможен (`password`), но в etcd он лежит открытым текстом. В бою это
  отдельный секрет (или KMS-расшифровка), здесь — чтобы показать, что такие поля и `jdbcUrl`
  выделены в отдельную группу и требуют пересоздания пула.
