# Tasks

## 1. Подключение плагина и аннотаций

- [x] 1.1 В `service/pom.xml`, `provisioner/pom.xml`, `loadgen/pom.xml` подключить
  `spotbugs-maven-plugin` 4.9.3.0: `effort=Max`, `threshold=Medium`, `failOnError=true`,
  execution с goal `check` (дефолтная фаза `verify`)
- [x] 1.2 В `service/pom.xml` и `provisioner/pom.xml` добавить
  `com.github.spotbugs:spotbugs-annotations` 4.9.3 (scope `provided`)
- [x] 1.3 В `Makefile` цель `test`: `mvn -B -q test` → `mvn -B -q verify` для service и provisioner

## 2. Исправления находок (service)

- [x] 2.1 `DbProperties.warningsFor`: сравнение boxed `Integer` через `==` → `Objects.equals`
- [x] 2.2 `PoolGauges`: две лямбды гейджей `pool.config.maximum_pool_size`/`pool.config.minimum_idle`
  — убрать пере-бокс (block-lambda, ветки `Integer`)
- [x] 2.3 `DemoItemsDao.countItems`: защита от NPE при распаковке `queryForObject`
- [x] 2.4 `EtcdProperties`: `getEndpoints` → `Collections.unmodifiableList`,
  `setEndpoints` → `List.copyOf`

## 3. Исправления находок (provisioner)

- [x] 3.1 `ProvisionerProperties`: `getEndpoints`/`setEndpoints` — как `EtcdProperties`

## 4. Точечные подавления с обоснованием

- [x] 4.1 Records service: `PoolSize.Normalized`, `EtcdKeys.Parsed`,
  `EtcdPoolConfigSource.EtcdStatus`, `ManagedPool.ApplyResult` — явные канонические
  конструкторы и акцессоры изменяемых компонентов с метод-уровневым `@SuppressFBWarnings`
  (класс-аннотация на record даёт `US_USELESS_SUPPRESSION_ON_CLASS`, spotbugs 4.9.3);
  `PoolSize` (компоненты только `Integer`) находок не даёт — аннотация не нужна
- [x] 4.2 Records provisioner: `ConnectionGrowth.Result`, `PoolSizeDistribution.Result` — то же;
  `NodeState`/`Decision` находок не дают, аннотации не требуют
- [x] 4.3 Хранение Spring-бинов в конструкторах: `TrafficGateFilter`, `DemoItemsDao`,
  `EtcdPoolConfigSource`, `PoolCounters`, `PoolGauges`, `HikariEventMetrics`, `ManagedPool`,
  `ConfigProvisioner`, `ProvisionerMetrics`; `PoolCounters.registry()` — на методе
- [x] 4.4 `ManagedPool.awaitAppliedChange` — подавление `RV_RETURN_VALUE_IGNORED_BAD_PRACTICE`
  (тактика окна: дождаться либо таймаута, затем читать состояние)

## 5. Интеграционная проверка

- [x] 5.1 `mvn verify` в каждом модуле — зелёный: тесты + гейт SpotBugs с нулём находок
- [x] 5.2 `make test` целиком — зелёный (юнит-тесты обоих модулей, spotbugs, promtool
  check config/rules + test rules)
- [x] 5.3 Живой стенд `make up`: 8 инстансов `ready=UP`, `keys=1`, `max=25`;
  `make budget`/`make leader` в норме, `ALERTS` пуст
- [x] 5.4 Заархивировать изменение (папка `openspec/changes/archive/2026-10-06-spotbugs-static-analysis/`)