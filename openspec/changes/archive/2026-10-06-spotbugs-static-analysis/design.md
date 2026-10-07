# Design

## Context

См. proposal.md. Три независимых Maven-проекта на общем `spring-boot-starter-parent` 3.5.16,
Java 21; корневого агрегатора нет. `make test` гоняет `mvn test` для service и provisioner,
loadgen вне цикла. Актуальные версии (search.maven.org, проверено запуском анализа):
плагин `spotbugs-maven-plugin` 4.9.3.0, аннотации `spotbugs-annotations` 4.9.3.
Одноразовый прогон анализа по текущему коду (без правок pom) дал 38 находок: 28 в service
(1 приоритет 1, остальные приоритет 2), 10 в provisioner (все приоритет 2), loadgen — 0.

## Goals / Non-Goals

**Goals:**
- Подключить SpotBugs ко всем трём проектам так, чтобы в `make test` любой баг приоритета 1–2
  валил сборку.
- Разобрать все текущие находки: реальные — исправить, намеренные — подавить точечно
  с обоснованием на месте, без `excludeFilterFile` и без классовых подавлений на здоровые классы.

**Non-Goals:**
- Переписывание records в классы с защитными копиями ради снятия `EI_EXPOSE_REP*`:
  компоненты record — контракт данных, менять его — менять поведение.
- `failThreshold`/`maxAllowedViolations`/`includeTests` не используются: полный гейт p2 задаётся
  `threshold=Medium` и дефолтным поведением `check`; тестовый код не анализируется
  (`includeTests=false` по умолчанию).
- Смена версии плагина после привязки, правки `docs/`, правки loadgen-кода — вне объёма.

## Decisions

**Версия и семантика гейта.** По исходнику `CheckMojo`/`BaseViolationCheckMojo` 4.9.3.0:
goal `check` (дефолтная фаза `verify`) через `@Execute(goal='spotbugs')` сначала гоняет анализ
(с конфигурацией плагина `effort=Max`, `threshold=Medium`), затем читает `target/spotbugsXml.xml`
и при непустом наборе `BugInstance` и `failOnError=true` валит сборку (`failed with N bugs`);
`failThreshold` не задан — все находки отчёта фатальны. Порог отчёта `Medium` включает
приоритеты 1 (High) и 2 (Medium) — это и есть полный гейт p2; находок приоритета 3 (Low)
в проектах сейчас нет, в гейт они не входят.

**`mvn verify` в `make test`.** `check` привязан к фазе `verify`; `mvn test` её не достигает,
поэтому цель `test` переводится на `mvn -B -q verify` для service и provisioner. Плата: в цикл
добавляются `package` (spring-boot repackage) и сам анализ — приемлемо для стенда. loadgen
остаётся вне `make test` (как сейчас); его пом подключён к `verify`, поэтому плагин сработает
при явном `mvn verify` или при добавлении модуля в цикл.

**Разбор находок — исправить (реальные проблемы).**

- `DbProperties.warningsFor:105` — `RC_REF_COMPARISON` (p1). `size.minimumIdle() ==
  size.maximumPoolSize()` — ссылочное сравнение boxed `Integer` (компоненты record `PoolSize`
  nullable). Работает только в диапазоне кэша `Integer` (−128..127); бюджет стенда 100 и потолок
  базы 120 — вне гарантированного диапазона. Fix: `Objects.equals`. Поведение в фактических
  диапазонах стенда не меняется (внутри кэша сравнение даёт тот же результат); вне — теперь
  корректно.
- `PoolGauges:89,91` — `BX_UNBOXING_IMMEDIATELY_REBOXED`. Тернарник `0 : size().maximumPoolSize()`
  (int/Integer) распаковывает и сразу пакует обратно. Fix: block-lambda с обеими ветками `Integer`
  (`Integer.valueOf(0)`), поставщик остаётся `Number`.
- `DemoItemsDao.countItems:26` — `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`: `queryForObject` может
  вернуть `null`, а метод возвращает `int` → NPE при распаковке. Fix: локальная переменная и
  `n == null ? 0 : n`.
- `EtcdProperties`/`ProvisionerProperties` — `EI_EXPOSE_REP`/`REP2` на `endpoints`. Getter отдаёт
  живую коллекцию, setter хранит переданную. Fix: getter — `Collections.unmodifiableList(...)`
  (SpotBugs неизменяемые обёртки не помечает), setter — `List.copyOf(...)`. Потребитель (клиент
  jetcd) читает список; Spring-биндинг пишет через setter и на копию не влияет.

**Разбор находок — подавить точечно (`@SuppressFBWarnings` с обоснованием).**

- Records, чьи компоненты — контракт данных по дизайну: `PoolSize.Normalized`,
  `EtcdKeys.Parsed`, `EtcdPoolConfigSource.EtcdStatus`, `ManagedPool.ApplyResult`,
  `ConnectionGrowth.Result`, `PoolSizeDistribution.Result`. Для ряда из них «экспозиция» — живая
  диагностическая проекция (`EtcdStatus.keys()/problems()`, `ApplyResult.changes()`,
  `ConnectionGrowth.Result.decisions()/losesConfig()`); защитные копии разорвали бы её. Механизм
  подавления — не класс-аннотация, а явный канонический конструктор и явные акцессоры
  изменяемых компонентов с аннотацией на каждом методе. Причина (подтверждена эмпирически на
  4.9.3): класс-аннотация на record с `EI_EXPOSE_REP*` подавляет находки, но параллельно
  порождает `US_USELESS_SUPPRESSION_ON_CLASS` — учёт сопоставленных супрессоров
  (`SuppressionMatcher.matched`) не фиксирует класс-супрессор record'а, и
  `UselessSuppressionDetector` объявляет полезную аннотацию бесполезной. Метод-уровневые
  подавления таким фейком не страдают (проверено: `PoolSize.Normalized` переведён раньше
  остальных — 12 находок records → 10). `PoolSize` и `ConnectionGrowth.{NodeState,Decision}`
  находок не дают (компоненты — примитивы/`String`/`Integer`), аннотаций не требуют.
- Хранение Spring-бинов в конструкторах (`EI_EXPOSE_REP2`): `TrafficGateFilter` (ObjectMapper),
  `DemoItemsDao` (JdbcTemplate), `EtcdPoolConfigSource` (ManagedPool, EtcdProperties),
  `PoolCounters` (MeterRegistry), `PoolGauges` (ManagedPool), `HikariEventMetrics` (MeterRegistry),
  `ManagedPool` (DbProperties), `ConfigProvisioner` (ProvisionerProperties, ProvisionerMetrics),
  `ProvisionerMetrics` (MeterRegistry). Это дирижирование бинами, «экспозиция» — дизайн:
  бин и так разделяется контейнером. Подавление на конструкторе; отдельно у
  `PoolCounters.registry()` (getter) — на методе.
- `ManagedPool.awaitAppliedChange:129` — `RV_RETURN_VALUE_IGNORED_BAD_PRACTICE`: результат
  `Condition.await(timeout)` игнорируется намеренно — тактика окна: дождаться либо таймаута,
  затем прочитать `appliedVersion`. Подавление на методе с обоснованием.

## Risks / Trade-offs

- [Порог гейта не тот: `check` валил бы не только на p1–p2] → `threshold=Medium` задан в
  конфигурации плагина; проверяется фактически: до правок `mvn verify` падает на 38 находках,
  после правок — зелёный. Дверь проверки — сам `make test`.
- [Точечные подавления скроют будущие находки в подавленных элементах] → аннотации ставятся на
  конструктор/метод/record-тип, а не на класс в целом (кроме records, где компоненты и так
  контракт); находка вне подавленного элемента валит сборку.
- [`mvn verify` замедлит `make test` за счёт package и анализа] → плата осознанная; `make up`
  от неё не зависит.
- [Правки `PoolGauges`/`DemoItemsDao` изменят ряды метрик] → значения и семантика не меняются,
  меняется только способ построения поставщика/защита от NPE.
- [Подавление на record не сработает в SpotBugs] → частично сбывшийся риск: класс-аннотация на
  record подавляет `EI_EXPOSE_REP*`, но даёт `US_USELESS_SUPPRESSION_ON_CLASS` (4.9.3), поэтому
  точками подавления стали явные акцессоры и канонические конструкторы (проверено фактическим
  прогоном `mvn verify` — зелёный). Решение «не переписывать record» не менялось.