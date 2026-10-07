# Proposal

## Why

В трёх самостоятельных Maven-проектах (service, provisioner, loadgen) нет статического анализа
кода. Стек — Java 21, Spring Boot 3.5.16, HikariCP 6.3.3, jetcd 0.8.7 — проверяется только
компилятором и юнит-тестами; класс ошибок вроде ссылочного сравнения boxed-`Integer` через `==`
(реальное срабатывание, приоритет 1, см. design) ничем не ловится. Подключается SpotBugs
(`com.github.spotbugs:spotbugs-maven-plugin` 4.9.3.0) во все три проекта с полным гейтом p2:
сборка падает на любой находке приоритета 1–2 (`threshold=Medium`), `make test` переводится на
`mvn verify`, чтобы гейт реально работал в основном цикле проверки. Тулинговое изменение —
`skip_specs: true`.

## What Changes

- `service/pom.xml`, `provisioner/pom.xml`, `loadgen/pom.xml`: подключается
  `spotbugs-maven-plugin` 4.9.3.0 с `effort=Max`, `threshold=Medium`, `failOnError=true`;
  goal `check` привязан к фазе `verify` (дефолтная фаза гола) — любой баг приоритета 1–2 из
  отчёта валит сборку.
- `service/pom.xml`, `provisioner/pom.xml`: добавляется `com.github.spotbugs:spotbugs-annotations`
  4.9.3 (scope `provided`) — только там, где ставятся `@SuppressFBWarnings`; в loadgen находок нет.
- `Makefile`: цель `test` переводится с `mvn -B -q test` на `mvn -B -q verify` для service и
  provisioner.
- Правки кода под текущие находки (38: 28 в service, 10 в provisioner, loadgen чист). Разбивка:
  - исправляется реальный баг `DbProperties.warningsFor`: сравнение `Integer` через `==` заменяется
    на `Objects.equals`;
  - убираются пере-боксы в лямбдах гейджей (`PoolGauges`) и потенциальный NPE в `DemoItemsDao`;
  - конфигурационные `endpoints` (`EtcdProperties`, `ProvisionerProperties`) защищаются
    неизменяемой обёрткой в getter'е и копией в setter'е;
  - намеренная экспозиция (record-компоненты, Spring-дирижирование бинами, сроковая семантика
    `await` в `ManagedPool.awaitAppliedChange`) — точечный `@SuppressFBWarnings` с обоснованием
    на месте.
- **BREAKING**: нет. Поведение на проволоке, метрики, API, инварианты AGENTS.md не меняются;
  `docs/` не правятся.

## Capabilities

### New Capabilities

Нет: поведение системы не меняется, изменение тулинговое.

### Modified Capabilities

Нет. Спек-контракты (`pool-lifecycle`, `etcd-config-source`, `config-validation`,
`pool-observability`, `traffic-readiness`, `demo-stand`, `instance-registration`,
`config-provisioner`) не затрагиваются. Опт-аут задан `skip_specs: true` в `.openspec.yaml`.
Единственная правка build-цикла — `make test` идёт через `mvn verify` (добавляется фаза
статического анализа); набор проверяемых артефактов тот же, плюс гейт SpotBugs.

## Impact

- Плагин во всех трёх `pom.xml`; зависимости аннотаций — в service и provisioner.
- `Makefile` `test`: `mvn -B -q test` → `mvn -B -q verify` (service, provisioner).
- Код, содержательные правки в шести файлах:
  - `service/.../config/DbProperties.java` (fix `==` → `Objects.equals`);
  - `service/.../config/EtcdProperties.java`, `provisioner/.../config/ProvisionerProperties.java`
    (защита `endpoints`);
  - `service/.../metrics/PoolGauges.java` (два поставщика гейджей без пере-бокса);
  - `service/.../dao/DemoItemsDao.java` (защита от NPE в `countItems`);
- Плюс ~20 точечных `@SuppressFBWarnings` с обоснованием в тех же модулях (records,
  Spring-бины, сроковая семантика `await`).
- Проверка: `mvn verify` в каждом модуле (тесты + гейт SpotBugs с нулём находок), `make test`
  зелёный, живой стенд — `make up`, `make pool`/`make budget`/`make leader`.