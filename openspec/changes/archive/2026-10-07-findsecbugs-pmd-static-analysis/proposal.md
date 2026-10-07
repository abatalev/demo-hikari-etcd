# Proposal

## Why

После подключения SpotBugs (гейт p1–p2) статический анализ покрывает общие дефекты, но два класса
ошибок остаются вне его охвата:

- **безопасность**: SpotBugs не имеет детекторов инъекций и криптографии. В стенде есть путь
  «сессия/пул/loadgen», но нет ни одной проверки класса `XSS`, к которому относится запись в
  ответе сервлета: единственная такая точка (`TrafficGateFilter`) не была бы замечена никем;
- **мёртвый код**: неиспользуемые import'ы SpotBugs не видит вовсе, а неиспользуемые локальные
  переменные и private-поля — частично. Фактический прогон нашёл и то и другое: мёртвое поле
  `spans` в `EtcdConfigWorker` (пережиток разделения фасада) и лишние импорты в двух классах.

Подключаются два детектора во все три проекта: findsecbugs 1.14.0 (плагин-детектор внутрь уже
стоящего `spotbugs-maven-plugin`) и `maven-pmd-plugin` 3.28.0 (правила неиспользуемого кода
PMD 7.17.0) с общим ruleset'ом в корне репозитория. Оба `check`-гола привязаны к фазе `verify`
(дефолтная фаза), то есть гейт работает в `make test` без новых команд. Тулинговое изменение —
`skip_specs: true`.

## What Changes

- `service/pom.xml`, `provisioner/pom.xml`, `loadgen/pom.xml`: в конфигурацию существующего
  `spotbugs-maven-plugin` 4.9.3.0 добавляется детектор `com.h3xstream.findsecbugs:findsecbugs-plugin`
  1.14.0 (собран против core 4.8.6, scope `provided` — с нашим 4.9.3 совместим; апгрейд SpotBugs
  не требуется). Гейт остаётся прежним: `threshold=Medium`, `effort=Max`, `failOnError=true`.
- Те же три pom: подключается `maven-pmd-plugin` 3.28.0 (за собой тянет PMD 7.17.0) с общим
  `pmd-ruleset.xml` в корне репозитория: `UnusedLocalVariable`, `UnusedPrivateField`,
  `UnnecessaryImport` (в PMD 7 он поглотил `UnusedImports`). Goal `check` привязан к `verify`
  (дефолтная фаза), `failOnViolation=true`.
- Правки кода под находки (10: 7 в service, 3 в provisioner, loadgen чист):
  - service: `TrafficGateFilter.doFilterInternal` — подавление `XSS_SERVLET` на методе
    (фейк: пишется JSON с внутренней причиной из закрытого набора, контент-тип `application/json`);
  - service: `EtcdConfigWorker` — удалено мёртвое поле/параметр `spans` (после разделения фасада
    конфиг-воркеру spans не нужен — применение пишет свои spans внутри `ManagedPool`), из
    `EtcdPoolConfigSource` убран соответствующий аргумент конструктора;
  - service: лишние импорты — `PoolGauges` (`org.slf4j.Logger`, поле пишется FQN) и
    `EtcdConfigWorker` (`MechanismSpans`);
  - service и provisioner: `try`-ресурсы, не упоминаемые в теле (watch-`Watcher`, `Event`, `Scope`),
    переименованы в `ignored` — документированный фильтр правила `UnusedLocalVariable` (имена,
    начинающиеся с `ignored`/`unused`, не считаются мусором).
- **BREAKING**: нет. Поведение на проволоке, метрики, API, инварианты AGENTS.md не меняются;
  `docs/` не правятся; docker-сборка не затрагивается (оба `check`-гола живут на `verify`,
  образы собираются `package`).

## Capabilities

### New Capabilities

Нет: поведение системы не меняется, изменение тулинговое.

### Modified Capabilities

Нет. Спек-контракты не затрагиваются, опт-аут задан `skip_specs: true` в `.openspec.yaml`.
Меняется только состав проверок сборки: к `mvn verify` (уже гоняющему SpotBugs) добавляются
детекторы безопасности findsecbugs и правила неиспользуемого кода PMD.

## Impact

- Плагины во всех трёх `pom.xml`; общий `pmd-ruleset.xml` в корне репозитория.
- Правки кода в семи файлах:
  - `service/.../controller/TrafficGateFilter.java` — подавление `XSS_SERVLET`;
  - `service/.../etcd/EtcdConfigWorker.java` — удаление мёртвого `spans`, лишний импорт,
    `watcher` → `ignored`;
  - `service/.../etcd/EtcdPoolConfigSource.java` — убран аргумент `spans` вызова воркера;
  - `service/.../metrics/PoolGauges.java` — лишний импорт;
  - `service/.../otel/MechanismSpans.java` — три `try`-ресурса → `ignored`;
  - `provisioner/.../etcd/ProvisioningWorker.java` — `watcher` → `ignored`;
  - `provisioner/.../otel/MechanismSpans.java` — три `try`-ресурса → `ignored`.
- Проверка: `mvn verify` в каждом модуле (тесты + SpotBugs/findsecbugs + PMD с нулём находок),
  `make test` зелёный, живой стенд — `make pool`/`make budget`/`make leader`, `ALERTS` пуст.