# Hikari-пул, который настраивается через etcd, на 8 инстансах

Прототип: размер пула соединений каждого инстанса к PostgreSQL 16 меняется **на лету** правкой
ключа в etcd. Compose-стенд: PostgreSQL 16, etcd, 8 инстансов сервиса (Spring Boot 3 + HikariCP),
2 балансировщика nginx (граница — по сервису), нагрузчик и образ с etcdctl.

```
   etcdctl put (правка размера)
   /config/services/{service}/groups/{group}/instances/{instance}/hikari/maximumPoolSize = 25
            │  8 путей, по одному на инстанс
            ▼
  ┌───────────────── etcd (watch, jetcd, revision-aware) ─────────────────┐
  │                                                                        │
  ▼          ▼          ▼          ▼          ▼          ▼          ▼      ▼
 service-a  service-a  service-a  service-a  service-b  service-b  service-b  service-b
 group-1-1  group-1-2  group-2-1  group-2-2  group-1-1  group-1-2  group-2-1  group-2-2
   :18081     :18082     :18083     :18084     :18085     :18086     :18087     :18088
      \        |         /            \        |         /
       \       |        /  lb-a :8080  \       |        /  lb-b :8081
        service-a ←────── ┐             service-b ←─────────
   нагрузка → lb-a ───────┴─► service-a (только service-a)
   нагрузка → lb-b ───────────────────► service-b (только service-b)
                        │ JDBC (application_name = имя инстанса)
                        ▼
               PostgreSQL 16 (max_connections=200)
```

Ключ из etcd доезжает до нового числа коннектов — [docs/mechanism.md](docs/mechanism.md).
Инстанс не принимает трафик, пока не получил конфигурацию из etcd (гейт готовности, 503 на
`/api/work`) — подробности в [docs/operations.md](docs/operations.md#готовность-к-трафику).

## Быстрый старт

```bash
cp .env.example .env   # один раз; канонический список инстансов там же
make up                # собрать, поднять 8 инстансов + 2 lb, дождаться готовности, показать пулы
make load-logs         # смотреть, как нагрузка давит на пул
make set-size SIZE=25 I=service-a-group-1-1   # ← вот тут etcd меняет пул на живом инстансе
make pool              # сводка: ready/max/total/active/idle по всем 8 инстансам
make down              # остановить; make clean — вместе с данными (в т.ч. ключи etcd)
```

Лог инстанса в момент правки:

```
[etcd@4 [maximumPoolSize=put]] eager fill: пул расширен с 10 до 25 коннектов одним изменением
[etcd@4 [maximumPoolSize=put]] конфигурация по пути /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/ получена, открываем трафик
```

**Что нужно на машине:** Docker + `make` + `curl` + `python3` (и `make up` зовёт `python3`).
Maven нужен только для `make test`. Локальный запуск без compose и режим `ETCD_ENABLED=false`
(единственный способ подняться без etcd — тогда гейта нет) — [docs/operations.md](docs/operations.md).

## Ключи конфигурации в etcd

Путь конфигурации инстанса:

```
{ETCD_ROOT}/services/{service}/groups/{group}/instances/{instance}/hikari/<ключ>
/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/maximumPoolSize
```

Сегменты: `service` — имя сервиса (`SERVICE_NAME`, фолбэк `spring.application.name`); `group` —
пространство имён (`ETCD_GROUP` → `POD_NAMESPACE`); `instance` — имя пода (`ETCD_INSTANCE` →
`POD_NAME`). При `ETCD_ENABLED=true` `group` и `instance` **обязательны**: пустой сегмент —
отказ на старте с указанием, какой сегмент не заполнен. Путь собирается сервисом сам, поэтому
расхождение «сервис смотрит в другой префикс, чем сидер» исключено по построению.

| ключ | пример | меняется на лету | комментарий |
|---|---|---|---|
| `maximumPoolSize` | `25` | да | главный рычаг, 1..200 |
| `minimumIdle` | `5` | да | не задан = держится равным `maximumPoolSize`; если меньше — в пуле появятся «спящие» коннекты, которые уходят по `idleTimeout` |
| `connectionTimeoutMs` | `3000` | да | сколько ждать свободный коннект |
| `idleTimeoutMs`, `maxLifetimeMs`, `validationTimeoutMs`, `leakDetectionThresholdMs` | | да | остальные таймауты HikariCP |
| `jdbcUrl`, `username`, `password`, `poolName` | | **нет** | только пересоздание пула |
| что угодно ещё | | | игнорируется с предупреждением (ловим опечатки) |

Диапазоны значений и что бывает за их нарушение — [docs/mechanism.md](docs/mechanism.md#валидация).
Локальные дефолты (`pool.db.*`) — фолбэк: ключ в etcd всегда выигрывает.

## Команды

Адресация инстансов: `I=имя` — один инстанс, `S=сервис` — все инстансы сервиса, `G=группа` —
все инстансы группы. Фактический расклад: `make instances`.

| команда | что делает |
|---|---|
| `make up` / `make down` / `make clean` | поднять (ждёт готовности всех с таймаутом) / остановить / остановить с томами |
| `make build` | пересобрать образы |
| `make ps` | состояние контейнеров |
| `make instances` | расклад: имя, порт, путь конфигурации по каждому инстансу |
| `make pool` / `make pool I=…` | сводка по всем инстансам / детально один |
| `make config [I=…]` | ключи инстанса в etcd |
| `make set-size SIZE=25 [I=…]` | `maximumPoolSize` одного инстанса |
| `make set-service-size SIZE=25 S=service-a` | размер пула всех инстансов сервиса |
| `make set-group-size SIZE=25 G=group-1` | размер пула всех инстансов группы |
| `make set-min-idle SIZE=3 [I=…]` | зафиксировать `minimumIdle` |
| `make unset-min-idle [I=…]` | снова follow за `maximumPoolSize` |
| `make set-conn-timeout SIZE=1000 [I=…]` | `connectionTimeoutMs` |
| `make work` | один запрос через `lb-a` (round-robin по service-a) |
| `make sessions` | сессии postgres по `application_name` всех пулов |
| `make psql` | консоль postgres |
| `make seed` | разложить стартовый конфиг по всем инстансам (идемпотентно) |
| `make stress` | разовый прогон нагрузки через `lb-a` (по умолчанию 20с) |
| `make logs P=service-a-group-1-1` | логи инстанса |
| `make load-logs` | логи нагрузчика |
| `make service-restart P=…` | перезапустить инстанс |
| `make test` | юнит-тесты (нужен maven) |

`make stress` без явного `DURATION_S` больше не зависает: для разового прогона он по умолчанию
20 секунд (для постоянной нагрузки работает `loadgen`-сервис — например, `docker compose
restart loadgen` или пусть крутится как есть).

## Топология и балансировка

- 8 инстансов: `service-a|service-b` × `group-1|group-2` × 2 пода. Имена подов —
  `service-a-group-1-1` и т.п., порты хоста 18081–18088.
- `lb-a :8080` → 4 инстанса `service-a`, `lb-b :8081` → 4 инстанса `service-b` (граница по сервису).
- Балансировка: round-robin nginx + `proxy_next_upstream error timeout http_502 http_503` +
  `max_fails=1 fail_timeout=5s`. Неготовый инстанс (503 от гейта) выпадает из ротации примерно
  на 5 секунд; открытый nginx активных health-чеков не имеет — это ограничение.
- Канонический список инстансов (`service|group|instance|port`) — `ETCD_INSTANCES` в `.env`. Его
  читают сидер и Makefile; блоки сервисов в `docker-compose.yml` должны совпадать с ним —
  расхождение видно через `make instances`.

## Готовность к трафику (гейт конфигурации)

Инстанс **не принимает трафик, пока не получил конфигурацию из etcd** (снимок с хотя бы одним
распознанным ключом). Пока гейт закрыт:

- `/api/work` отвечает **503** (фильтр), `/api/pool` и `/api/config` работают — неготовый
  инстанс виден;
- `/actuator/health/readiness` = DOWN (с причиной), `/actuator/health/liveness` = UP — живость
  от etcd не зависит, crashloop из-за etcd исключён;
- `notReadyReason` в `/api/config` объясняет, почему.

Гейт открывается **один раз и не откатывается**: после применения конфигурации обрыв etcd трафик
не останавливает. Отклонённая при проверке конфигурация (мусор в `maximumPoolSize`) гейт не
удерживает: значение получено, пул остаётся на последних рабочих значениях. `ETCD_PREFIX` больше
нет: есть `ETCD_ROOT` + сегменты. Старые ключи из старого префикса не читаются и удаляются вместе
с томом (`make clean`).

## Что умеет и чего не умеет

**Меняется на живом пуле, без пересоздания** — через `HikariConfigMXBean`:
`maximumPoolSize`, `minimumIdle`, `connectionTimeoutMs`, `idleTimeoutMs`, `maxLifetimeMs`,
`validationTimeoutMs`, `leakDetectionThresholdMs`. Запросы в полёте не страдают.

**Требует пересоздания пула** — `jdbcUrl`, `username`, `password`, `poolName`: MBean этого не
умеет. Перед закрытием старого пула ждём до `drain-on-recreate-timeout` (3 c), пока доработают
активные соединения.

Плохой конфиг не роняет сервис:

| что положили в etcd | что будет |
|---|---|
| `maximumPoolSize=0` | конфиг отклонён целиком (REJECTED), пул остался на прежнем размере, трафик не блокируется, причина в логе |
| `maximumPoolSize=banana` | только этот ключ проигнорирован (взят дефолт), соседние ключи применились, проблема видна в `/api/pool` → `etcd.problems` |
| `maxPoolSize=8` (опечатка) | предупреждение «неизвестный ключ» один раз, на конфиг не влияет |
| удалили ключ | значение возвращается к локальному дефолту |
| etcd лежит после применения | пул работает на последнем конфиге, готовность не сбрасывается, watch докатывается с backoff'ом |
| etcd лежит до первого применения | инстанс жив, готовность DOWN, /api/work=503; когда etcd вернётся — сам становится готовым |

## Демо, которое стоит пройти

```bash
make up
make pool                        # все 8 готовы, total=10 у каждого

make set-size SIZE=25 I=service-a-group-1-1   # один инстанс, отдельно от остальных
make pool                        # вырос только service-a-group-1-1
make set-group-size SIZE=3 G=group-1          # оба инстанса service-*-group-1

# увод трафика: инстанс не готов, lb-a его пропускает
docker compose stop service-a-group-1-2
make work                        # {'ok':true} — round-robin по остальным трём service-a
make pool                        # service-a-group-1-2 помечен n/a
docker compose start service-a-group-1-2
```

Как устроен нагрузчик (`loadgen/LoadGen.java`, один файл на JDK): `WORKERS` виртуальных потоков,
`WORK_MS` — сколько держать коннект в `pg_sleep`, `REPORT_MS` — период отчёта, `DURATION_S=0` —
крутится до `docker compose stop loadgen`. Каждая строка отчёта: rps, p50/p95/max, inflight,
ошибки + состояние пула + сессии postgres. `TARGET` — куда долбить (`lb-a`), `STATUS_TARGET` —
откуда брать сводку пула (стабильный инстанс, иначе отчёт мигает между инстансами группы).

## API

| эндпоинт | что делает |
|---|---|
| `GET /api/pool` | состояние пула, эффективный конфиг (пароль замаскирован), сессии postgres, статус etcd (path, revision, ключи, проблемы, notReadyReason) |
| `GET /api/config` | только etcd-часть: путь, ключи, ошибки, причина неготовности |
| `GET /api/work?ms=20` | взять коннект, сделать запрос, поспать в БД; **503**, пока гейт закрыт |
| `GET /actuator/health/readiness` | готовность: гейт конфигурации (`readinessState` + `poolEtcd`) и база |
| `GET /actuator/health/liveness` | живость: только `ping`, от etcd и базы не зависит |

С примерами ответа и разбором полей — [docs/api.md](docs/api.md). В `pool_sessions` пулы
различаются по `application_name` (равен `POOL_NAME` = имени инстанса) — view показывает каждый
пул отдельной строкой. Если пул занят полностью, `/api/pool` пропускает интроспекцию сессий —
смотрите `make sessions`.

## Документация

| файл | о чём |
|---|---|
| [docs/mechanism.md](docs/mechanism.md) | путь `put` → resize: watch, валидация, порядок сеттеров, shrink, eager fill, recreate с дренажем |
| [docs/api.md](docs/api.md) | примеры ответов с разбором полей, неочевидные значения |
| [docs/experiments.md](docs/experiments.md) | что реально получалось при стенде, с цифрами |
| [docs/operations.md](docs/operations.md) | troubleshooting, переменные окружения, локальный запуск, гейт готовности, ограничения |
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
`/opsx-explore`, `/opsx-sync`, `/opsx-update`. Артефакты на русском; структурные заголовки и
`SHALL`/`MUST` — английские. Планирование и реализация разнесены: `/opsx-propose` создаёт только
артефакты, код трогается после отдельного запроса на `/opsx-apply`.

## Состав

```
service/       Spring Boot 3.5 + HikariCP + jetcd        (mvn test)
  pool/        ManagedPool, HikariSettings (валидация)   — сердце прототипа
  etcd/        EtcdPoolConfigSource (watch+гейт), EtcdKeys, EtcdKeyPath
  web/         PoolController, TrafficGateFilter (503)
  health/      EtcdConfigHealthIndicator (причина неготовности)
loadgen/       нагрузчик на голом JDK (TARGET / STATUS_TARGET)
nginx/         шаблон конфига балансировщика (envsubst: BACKEND_1..4)
etcd/          образ с etcdctl + сидинг по ETCD_INSTANCES
scripts/       wait-ready.sh (make up с таймаутом), pool-all.py (сводка)
db/init/       демо-таблица + view pool_sessions
openspec/      спецификации и изменения (config.yaml, specs/, changes/)
.opencode/     слэш-команды и скилы OpenSpec для OpenCode
```

Тестов 20: `HikariSettingsTest` (валидация, дефолты, diff), `EtcdKeysTest` (разбор ключей),
`EtcdKeyPathTest` (путь и валидация сегментов) — юнит-тесты без etcd и без БД.

## Решения, которые стоит знать

- **Путь собирается из сегментов, а не берётся строкой.** `ETCD_PREFIX` удалён: сервис сам
  строит `{root}/services/{service}/groups/{group}/instances/{instance}/hikari/`, поэтому «сервис
  смотрит в другой префикс, чем сидер» невозможно по построению. Плата: пустые `group`/`instance`
  при включённом etcd — отказ на старте.
- **Гейт трафика живёт в источнике конфигурации**, открывается первым распознанным ключом и
  никогда не откатывается. Живость процесса от etcd не зависит (`liveness` = `ping`).
- **Пул создаётся один раз на весь процесс жизни приложения.** `ManagedPool` реализует
  `DataSource` и делегирует вызовы живому `HikariDataSource`: `JdbcTemplate` и менеджер транзакций
  следуют за подменой пула сами, без пересоздания.
- **`minimumIdle` по умолчанию следует за `maximumPoolSize`** (как в HikariCP). Иначе сжатие пула
  падало бы с `minimumIdle > maximumPoolSize`.
- **Снимок + watch с `revision + 1`.** Разрыва между чтением и подпиской нет; любой обрыв
  приводит к новому снимку.
- **`eager-fill-on-resize=true`** (дефолт стенда) — коннекты после увеличения добиваются сразу.
  В проде обычно `false`.
- **`initialization-fail-timeout=-1`** — сервис поднимается, даже если БД ещё не готова.
- **`mem_limit: 512m` на инстанс** — 8 JVM без лимитов разнесли бы хост.
- **Пароль в etcd** возможен (`password`), но лежит открытым текстом; в `/api/*` он маскируется.