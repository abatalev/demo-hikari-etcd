# Tasks

## 1. Подключение плагинов

- [x] 1.1 В `service/pom.xml`, `provisioner/pom.xml`, `loadgen/pom.xml`: внутрь конфигурации
  `spotbugs-maven-plugin` добавлен детектор `findsecbugs-plugin` 1.14.0
- [x] 1.2 В тех же трёх pom подключён `maven-pmd-plugin` 3.28.0 (PMD 7.17.0): ruleset
  `../pmd-ruleset.xml`, `failOnViolation=true`, goal `check` на дефолтной фазе `verify`
- [x] 1.3 Создан общий `pmd-ruleset.xml` в корне репозитория: `UnusedLocalVariable`,
  `UnusedPrivateField`, `UnnecessaryImport`

## 2. Разбор находок (service)

- [x] 2.1 `TrafficGateFilter.doFilterInternal` — метод-уровневое подавление `XSS_SERVLET`
  с обоснованием (JSON внутренней причины, контент-тип application/json; фейк findsecbugs)
- [x] 2.2 `EtcdConfigWorker` — удалено мёртвое поле/параметр `spans`; `EtcdPoolConfigSource`
  — убран аргумент вызова воркера
- [x] 2.3 Лишние импорты: `PoolGauges` (`org.slf4j.Logger`), `EtcdConfigWorker` (`MechanismSpans`)
- [x] 2.4 `MechanismSpans` — `try`-ресурсы `Event e`/`Scope scope` переименованы в `ignored`
  (документированный фильтр `UnusedLocalVariable`; 3 шт.)

## 3. Разбор находок (provisioner)

- [x] 3.1 `ProvisioningWorker.watch` — `Watch.Watcher watcher` → `ignored`
- [x] 3.2 `MechanismSpans` — `Event e`/`Scope scope` → `ignored` (3 шт.)

## 4. Интеграционная проверка

- [x] 4.1 `mvn verify` в каждом модуле — зелёный: тесты + SpotBugs/findsecbugs + PMD
  с нулём находок (до правок падает: service 1+6, provisioner 4)
- [x] 4.2 `make test` целиком — зелёный (exit=0: verify ×2, check-silence/check-instances,
  door-config, promtool check config/rules + test rules)
- [x] 4.3 Живой стенд после пересборки образов (`make up`): 8 инстансов `ready=UP`, `max=25`,
  `keys=1`; `make budget` — N=100, сумма долей 100; `make leader` — по реплике на сервис;
  `ALERTS` пуст
- [x] 4.4 `openspec validate` + архивация изменения
  (`openspec/changes/archive/2026-10-07-findsecbugs-pmd-static-analysis/`)