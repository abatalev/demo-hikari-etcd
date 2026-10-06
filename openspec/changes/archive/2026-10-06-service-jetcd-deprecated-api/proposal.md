# Proposal

## Why

В jetcd 0.8.7 (версия зафиксирована в `service/pom.xml` и `provisioner/pom.xml`) все классы опций
переведены на новую конвенцию: `newBuilder()` заменён на `builder()`, а `withPrefix(ByteSequence)`
на `isPrefix(boolean)`. Код сервиса после разделения `EtcdPoolConfigSource` держит четыре вызова
deprecated-API (два в `EtcdConfigWorker`, по одному в `UnreleasedShrinkPublisher` и
`InstanceRegistration`); провизор — ещё семь (четыре в `ProvisioningWorker`, три в
`ConfigProvisioner`). Вызовы работают, но предупреждения компилятора и устаревание API копятся;
переход на актуальный API ничего не меняет в поведении (см. design — запрос на проволоке
идентичен), поэтому это рефакторинг, легализуемый `skip_specs: true`.

## What Changes

- `EtcdConfigWorker.java`: `GetOption.newBuilder()` → `GetOption.builder()` в снимке пути;
  `WatchOption.newBuilder().withPrefix(prefix)` → `WatchOption.builder().isPrefix(true)` в watch
  (watch-key остаётся самим префиксом, `withRevision`/`withPrevKV` без изменений).
- `UnreleasedShrinkPublisher.java`: `PutOption.newBuilder()` → `PutOption.builder()`.
- `InstanceRegistration.java`: `PutOption.newBuilder()` → `PutOption.builder()`.
- `ProvisioningWorker.java`: `GetOption.newBuilder()` ×2 и `DeleteOption.newBuilder()` → `builder()`;
  `WatchOption.newBuilder().withPrefix(prefix)` → `WatchOption.builder().isPrefix(true)` (watch-key —
  тот же префикс поддерева сервиса).
- `ConfigProvisioner.java`: `GetOption.newBuilder()` → `builder()` в `scanServices`;
  `GetOption.newBuilder().withPrefix(bs(prefix))` → `GetOption.builder().isPrefix(true)` в выборках
  (`withSortField`/`withSortOrder`/`withLimit(1)` без изменений — диапазон тот же, см. design);
  `PutOption.newBuilder()` → `PutOption.builder()`.
- **BREAKING**: нет. Запросы к etcd, метрики, API, имена потоков и инварианты AGENTS.md не
  меняются; `docs/` не правятся.

## Capabilities

### New Capabilities

Нет: поведение не меняется, изменение чисто гигиеническое (замена устаревших вызовов API).

### Modified Capabilities

Нет: требования capabilities (`etcd-config-source`, `instance-registration`, `config-validation`,
`pool-observability`, `traffic-readiness`, `config-provisioner`) не затрагиваются. Опт-аут от спек
задан `skip_specs: true` в `.openspec.yaml`.

## Impact

- Изменяются одиннадцать вызовов в пяти файлах:
  - `service/src/main/java/com/abatalev/demo/etcdhikari/service/etcd/EtcdConfigWorker.java`
    (две строки), `.../etcd/UnreleasedShrinkPublisher.java`, `.../etcd/InstanceRegistration.java`
    (по одной);
  - `provisioner/src/main/java/com/abatalev/demo/etcdhikari/provisor/etcd/ProvisioningWorker.java`
    (пять строк: 212, 269, 785, 806–807), `.../etcd/ConfigProvisioner.java` (три: 287, 305–306, 349).
- Зависимости (jetcd остаётся `0.8.7` в обоих модулях), конфигурация, метрики, API, доки не
  меняются.
- Проверка: `make test` (юнит-тесты обоих модулей + promtool) и живой стенд — `make up` с проверкой
  `make pool`/`make budget`/`make leader`, что конфигурация применяется, гейт открыт, лидер выборов
  на месте, ключи бюджета в норме.