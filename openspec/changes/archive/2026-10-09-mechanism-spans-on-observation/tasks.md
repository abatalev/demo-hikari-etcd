# Tasks

## 1. Прототип: атрибуты события после start в bridge-otel

- [x] 1.1 В `service` собрать тест-прототип: `ObservationRegistry` + handler `micrometer-tracing-bridge-otel` + `SdkTracerProvider` с `InMemorySpanExporter` (test-scope `opentelemetry-sdk-testing`), и убедиться, что key-values, добавленные в контекст наблюдения после `start()`, присутствуют в завершённом спане. При отсутствии — зафиксировать итог в контексте и применять при `stop()` (фолбэк D5). Проверка: тест падает/проходит на прямом `context.put(...)` после start.

## 2. service: замена контура

- [x] 2.1 Добавить `MechanismObservation` в `service` (обёртка над `ObservationRegistry`): `event`, `start`/`Event(note/failure/close)`, `call`/`callQuietly`/`run`; признак-лямбда исполняется до старта, её ошибка гасится с warn; тело события выполняется всегда; имена спанов те же. Удалить `MechanismSpans` (собственный провайдер, alwaysOn, батч-процессор, forceFlush, NOOP, SmartLifecycle). Проверка: `mvn -q -pl service compile` — в `service` нет ссылок на `MechanismSpans`.
- [x] 2.2 Перенацелить точки вызова в `service` на `MechanismObservation`: `ManagedPool` (`pool.config.apply`, `pool.drain`, `pool.acquire.wait`), `UnreleasedShrinkPublisher` (`etcd.drain_debt.publish`), `EtcdPoolConfigSource`, `PoolBeansConfiguration` (бин). Зависимость — `ObservationRegistry` Boot'а. Проверка: `mvn -q -pl service test` зелёный, в коде не осталось `setNoParent`/`addLink`/`serving.trace_id`.

## 3. provisioner: замена контура

- [x] 3.1 Та же замена в `provisioner`: обёртка `MechanismObservation`, перенацелить `ProvisioningWorker` (`provisioner.fleet.recompute`, `etcd.fleet.write.ceiling`, `etcd.fleet.wipe`) и `ConfigProvisioner` (`provisioner.election.won/lost`, `mechanism()`). Проверка: `mvn -q -pl provisioner test` зелёный, ссылок на `MechanismSpans` нет.

## 4. Тесты

- [x] 4.1 Переписать `MechanismSpansTest` в `service` под Observation (в т.ч. прототип 1.1 вместо тестов собственного провайдера): признак-лямбда упала → тело выполнилось и событие записано; тело бросило → событие помечено ошибкой; `note` после старта виден в завершённом спане. Удалить тесты alwaysOn/очереди/flush. Проверка: `mvn -q -pl service test` зелёный.
- [x] 4.2 То же в `provisioner`. Проверка: `mvn -q -pl provisioner test` зелёный.
- [x] 4.3 Поправить использования `MechanismSpans.NOOP` в тестах (`PoolGaugesTest` и др.): подставлять `MechanismObservation` с независимым реестром. Проверка: зелёные тесты `service` и `provisioner`.

## 5. Конфигурация и документация

- [x] 5.1 Обновить комментарий к `management.tracing.sampling.probability` в `application.yml` обоих приложений: доля применима и к событиям механизма. Проверка: `grep -n "sampling" service/src/main/resources/application.yml provisioner/src/main/resources/application.yml`.
- [x] 5.2 Переписать в `AGENTS.md` абзац «События механизма отбираются в трассу всегда…» на общую долю и родительство (событие в потоке запроса — часть его трассы). Проверка: `grep -n "всегда" AGENTS.md` не находит утверждения о трассах механизма.
- [x] 5.3 Поправить термин «Событие механизма» в `docs/glossary.md` (убрать «всегда, независимо от OTEL_TRACES_SAMPLING»). Проверка: grep по glossary не находит «всегда».
- [x] 5.4 Поправить `docs/operations.md`: описание доли отбора (строки с «отбираются всегда»), раздел «Доля отбора трасс», строку troubleshooting «трассы инстанса нет». Проверка: grep «OTEL_TRACES_SAMPLING» в docs не утверждает «всегда».

## 6. Интеграция и стенд

- [x] 6.1 `make test` целиком зелёный (юнит-тесты трёх модулей, сверки состава, promtool check config/rules/test rules).
- [x] 6.2 Стенд при доле 1.0: в Tempo по признаку `node` инстанса есть события `pool.config.apply` (переход) и у провизёра `provisioner.fleet.recompute`; `pool.acquire.wait` под нагрузкой виден внутри трассы запроса. Проверка: поиск по хранилищу трасс.
- [x] 6.3 Стенд при `OTEL_TRACES_SAMPLING=0.01`: событие перехода может отсутствовать в Tempo, журнал инстанса содержит запись о применении; пул, конфигурация и провижинирование работают как раньше. Проверка: переключение доли и сверка журналов.