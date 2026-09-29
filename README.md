# Hikari-пул, который настраивается через etcd, на 8 инстансах

Прототип: размер пула соединений каждого инстанса к PostgreSQL 16 меняется **на лету** правкой
ключа в etcd. Compose-стенд: PostgreSQL 16, etcd, 8 инстансов сервиса (Spring Boot 3 + HikariCP),
2 балансировщика nginx (граница — по сервису), два провизора конфигурации (лидер выбирается по
каждому сервису через etcd), нагрузчик и образ с etcdctl.

```
   etcdctl put (бюджет сервиса)   провизёр делит бюджет между живыми инстансами сервиса
   /config/services/{service}/maxConnections = 100   →   .../hikari/maximumPoolSize = 25
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
make up                # собрать, поднять 8 инстансов + 2 lb + 2 провизора, дождаться готовности, показать пулы
make load-logs         # смотреть, как нагрузка давит на пул
make leader            # кто ведёт каждый сервис (лидер выборов провизора)
make set-max-connections SIZE=100 S=service-a  # ← вот тут etcd меняет пул на живых инстансах
make budget S=service-a           # доли по сервису и их сумма (сумма = бюджет)
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
отказ на старте с указанием, какой сегмент не заполнен. Путь собирается сервисом сам, и провизор
собирает его по тем же правилам, поэтому расхождение «провижер пишет в другой префикс, чем читает
сервис» исключено по построению.

| ключ | пример | меняется на лету | комментарий |
|---|---|---|---|
| `maximumPoolSize` | `25` | да | **доля бюджета сервиса**, 1..200; ключ управляемый — провизёр перезаписывает его при пересчёте |
| `minimumIdle` | `5` | да | не задан = держится равным `maximumPoolSize`; если меньше — в пуле появятся «спящие» коннекты, которые уходят по `idleTimeout` |
| `connectionTimeoutMs` | `3000` | да | сколько ждать свободный коннект |
| `idleTimeoutMs`, `maxLifetimeMs`, `validationTimeoutMs`, `leakDetectionThresholdMs` | | да | остальные таймауты HikariCP |
| `jdbcUrl`, `username`, `password`, `poolName` | | **нет** | только пересоздание пула |
| что угодно ещё | | | игнорируется с предупреждением (ловим опечатки) |

Второй уровень дерева — **бюджет соединений сервиса**. Эти два ключа читает и пишет только
провизёр, инстанс их не видит:

| ключ | пример | комментарий |
|---|---|---|
| `{ETCD_ROOT}/services/{service}/maxConnections` | `100` | бюджет N: сумма долей всех инстансов сервиса; делится поровну, 1.. |
| `{ETCD_ROOT}/services/{service}/minConnections` | `1` | минимальная доля m: обслуживаются только `floor(N/m)` инстансов |

Диапазоны значений и что бывает за их нарушение — [docs/mechanism.md](docs/mechanism.md#валидация).
Локальные дефолты (`pool.db.*`) — фолбэк: ключ в etcd всегда выигрывает.

## Регистрация инстанса в etcd

Каждый инстанс при включённом источнике на старте создаёт в etcd **узел со своим именем** — на
уровень выше ключей конфигурации:

```
{ETCD_ROOT}/services/{service}/groups/{group}/instances/{instance}
```

Узел держится через аренду etcd (TTL по умолчанию 15с, keepalive каждые 5с) и исчезает при
остановке: штатной — немедленно (аренда отзывается), при падении процесса — по истечении TTL.
После обрыва etcd регистрация восстанавливается сама. `make registrations` показывает живые узлы.

Узел лежит **вне** префикса `hikari/`, поэтому конфиг-воркер его не читает: на готовность и гейт
трафика регистрация не влияет — «зарегистрирован, но не готов» это нормальное состояние (трафик
открывается только после распознанных ключей `hikari/`). При `ETCD_ENABLED=false` регистрации нет.
Подробности — [docs/mechanism.md](docs/mechanism.md#регистрация-инстанса-в-etcd).

## Провижирование конфигурации

Конфигурацию раскладывает не разовый сидер, а отдельный сервис **провижер**, запускаемый
**двумя репликами** (`config-provisioner-1`, `config-provisioner-2`), который следит за деревом
`{root}/services/` и держит инвариант: **ключи конфигурации инстанса существуют тогда и только
тогда, когда существует его узел регистрации и узел получил долю сервисного бюджета**.

- Работает ровно одна реплика на сервис: лидер выбирается через etcd узлами
  `{root}/provisioner/leader/{service}/` (лидер — ключ с наименьшим `create_revision`, значение —
  имя реплики). Упал лидер → второй перехватывает сервис в пределах `PROV_LEADER_TTL` (10с) и
  делает полную сверку. Кто ведёт: `make leader`. Подробности — [docs/mechanism.md](docs/mechanism.md#выборы-лидера).
- **Размер пула — доля сервисного бюджета.** Бюджет лежит в etcd:
  `{root}/services/{service}/maxConnections` (N) и `.../minConnections` (минимальная доля m).
  Один ключ на сервис, делится поровну между живыми инстансами, сумма долей = N. Ключи создаются
  при первом узле сервиса дефолтами провизера (`PROV_MAX_CONNECTIONS` = 100,
  `PROV_MIN_CONNECTIONS` = 1) только если их ещё нет, и переживают опустение сервиса — этого
  требует rolling update.
- `maximumPoolSize` — **управляемый** ключ: лидер приводит его к доле при каждом пересчёте, то
  есть при изменении бюджета, появлении или исчезновении инстанса, а также на старте и после
  обрыва etcd. `connectionTimeoutMs` и остальные ключи — обычный put-if-absent: ручные правки
  переживают всё.
- Инстанс, которому доля не досталась (`n × m > N`), остаётся без конфигурации и не обслуживает
  трафик: `/api/work` отвечает 503, `readiness` DOWN. При `N < m` провижёр не меняет ничего и пишет
  WARN (fail-closed — опечатка в etcd не должна обнулить живой сервис).
- Инстанс исчез (штатная остановка, краш, истекла аренда по TTL) → лидер **удаляет весь**
  префикс `.../{instance}/hikari/`, включая ключи, добавленные вручную.
- События ключей `hikari/*`, кроме `maximumPoolSize`, провижёр игнорирует — петель нет. Записи
  лидера идемпотентны, поэтому окно перехвата безопасно.

Порядок старта в compose не важен: инстансы больше не ждут сидера — провижёр нагоняет
конфигурацию по факту регистрации (сверка + watch с `revision+1`). Окно «зарегистрирован, но ещё
не провижен» — нормальное состояние, гейт трафика закрывает его до первого ключа.

Следствие для ручных правок: `maximumPoolSize` живёт **до ближайшего пересчёта** (`make set-size` —
временный оверайд), остальные значения — **пока жив узел инстанса**. Подробности —
[docs/mechanism.md](docs/mechanism.md#распределение-сервисного-бюджета).

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
| `make registrations` | узлы регистрации инстансов в etcd (кто сейчас жив) |
| `make leader` | кто ведёт каждый сервис (лидер выборов провизора) |
| `make budget [S=сервис]` | бюджет сервиса, доли по инстансам и их сумма |
| `make set-max-connections SIZE=100 S=service-a` | **бюджет соединений сервиса N** (делится между живыми инстансами) |
| `make set-min-connections SIZE=15 S=service-a` | **минимальная доля инстанса m** |
| `make set-size SIZE=25 [I=…]` | оверайд `maximumPoolSize` одного инстанса (живёт до пересчёта) |
| `make set-service-size SIZE=25 S=service-a` | оверайд `maximumPoolSize` всех инстансов сервиса |
| `make set-group-size SIZE=25 G=group-1` | оверайд `maximumPoolSize` всех инстансов группы |
| `make set-min-idle SIZE=3 [I=…]` | зафиксировать `minimumIdle` |
| `make unset-min-idle [I=…]` | снова follow за `maximumPoolSize` |
| `make set-conn-timeout SIZE=1000 [I=…]` | `connectionTimeoutMs` |
| `make work` | один запрос через `lb-a` (round-robin по service-a) |
| `make sessions` | сессии postgres по `application_name` всех пулов |
| `make psql` | консоль postgres |
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
  читают compose (раскладка сервисов) и Makefile (адресация ручных правок); блоки сервисов в
  `docker-compose.yml` должны совпадать с ним — расхождение видно через `make instances`.

## Готовность к трафику (гейт конфигурации)

Инстанс **не принимает трафик, пока не получил конфигурацию из etcd** (снимок с хотя бы одним
распознанным ключом). Пока гейт закрыт:

- `/api/work` отвечает **503** (фильтр), `/api/pool` и `/api/config` работают — неготовый
  инстанс виден;
- `/actuator/health/readiness` = DOWN (с причиной), `/actuator/health/liveness` = UP — живость
  от etcd не зависит, crashloop из-за etcd исключён;
- `notReadyReason` в `/api/config` объясняет, почему.

Гейт закрывается, когда распознанных ключей не осталось (провижер снял конфигурацию — инстанс
потерял долю бюджета), и открывается снова при возврате ключей. Закрытие происходит только по
реальным событиям удаления и снимкам при живом etcd: обрыв etcd трафик не останавливает.
Отклонённая при проверке конфигурация (мусор в `maximumPoolSize`) гейт не
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

make set-max-connections SIZE=120 S=service-a   # бюджет 120 на 4 инстанса service-a
make budget S=service-a                        # доли 30/30/30/30, сумма 120
make pool                                      # у всех инстансов service-a maximumPoolSize=30

make set-max-connections SIZE=60 S=service-a   # 60 / 4 = по 15 у каждого
make set-min-connections SIZE=40 S=service-a   # 60 / 40 = 1 -> трое без доли и 503
make budget S=service-a
make set-min-connections SIZE=1 S=service-a    # вернуть всех в обслуживание
make registrations               # 8 живых узлов регистрации

# провижирование: инстантные ключи живут, пока жив узел
docker compose stop service-a-group-1-1       # узел исчез -> провизор удалил весь префикс hikari/*
make registrations               # 7 узлов
make budget S=service-a                        # 3 инстанса делят 100: 34/33/33
make config I=service-a-group-1-1             # пусто — вместе с ручными правками стёрлись все ключи
docker compose start service-a-group-1-1      # узел вернулся -> провижёр прислал долю бюджета
make config I=service-a-group-1-1             # maximumPoolSize=34, connectionTimeoutMs=3000

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
provisioner/   два провизора с выборами лидера по сервису: сверка+watch, txn put_if_absent, GC ключей
loadgen/       нагрузчик на голом JDK (TARGET / STATUS_TARGET)
nginx/         шаблон конфига балансировщика (envsubst: BACKEND_1..4)
etcd/          образ с etcdctl для ручных правок
scripts/       wait-ready.sh (make up с таймаутом), pool-all.py (сводка), leaders.py (make leader)
db/init/       демо-таблица + view pool_sessions
openspec/      спецификации и изменения (config.yaml, specs/, changes/)
.opencode/     слэш-команды и скилы OpenSpec для OpenCode
```

Юнит-тесты без etcd и без БД: `HikariSettingsTest` (валидация, дефолты, diff), `EtcdKeysTest`
(разбор ключей), `EtcdKeyPathTest` (путь и валидация сегментов) — у сервиса, и
`InstanceKeyTest`/`ElectionTimingsTest` (грамматика узла/конфигурации, набор живых сервисов,
интервал keepalive) — у провизора. Watch-циклы и выборы лидера проверяются на живом стенде
(Testcontainers в проекте нет).

## Решения, которые стоит знать

- **Путь собирается из сегментов, а не берётся строкой.** `ETCD_PREFIX` удалён: сервис сам
  строит `{root}/services/{service}/groups/{group}/instances/{instance}/hikari/`, поэтому «сервис
  смотрит в другой префикс, чем провизор» невозможно по построению. Плата: пустые `group`/`instance`
  при включённом etcd — отказ на старте.
- **Стартовые ключи кладёт провизор по жизни инстанса в etcd**, а не разовый сидер: появление
  узла регистрации → в путь добавляются отсутствующие `maximumPoolSize`/`connectionTimeoutMs`
  (существующие не перезаписываются), исчезновение узла → весь префикс `hikari/` удаляется.
  События самих ключей конфигурации провизор игнорирует — петель нет.
- **Провижер — это две реплики с выборами лидера по сервису** (`{root}/provisioner/leader/{service}/`),
  а не один процесс: набор сервисов выводится из живых узлов дерева (списка в конфигурации нет),
  роли симметричны, перехват краха лидера — в пределах TTL аренды. Записи идемпотентны, поэтому
  окно «двух писателей» при перехвате безопасно.
- **Гейт трафика живёт в источнике конфигурации**: открывается первым распознанным ключом и
  закрывается, когда распознанных ключей не осталось (провижер снял конфигурацию). Живость
  процесса от etcd не зависит (`liveness` = `ping`).
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