# Tasks

## 1. Замена deprecated-вызовов в service

- [x] 1.1 В `EtcdConfigWorker.snapshot()` заменить `GetOption.newBuilder()` на `GetOption.builder()`; проверить, что `mvn -pl service test` компилирует без предупреждений `deprecation` по jetcd
- [x] 1.2 В `EtcdConfigWorker.watch()` заменить `WatchOption.newBuilder().withPrefix(prefix)` на `WatchOption.builder().isPrefix(true)`, сохранив `withRevision(fromRevision)` и `withPrevKV(true)`; проверить `mvn -pl service test`
- [x] 1.3 В `UnreleasedShrinkPublisher` и `InstanceRegistration` заменить `PutOption.newBuilder()` на `PutOption.builder()`; проверить `mvn -pl service test`

## 2. Замена deprecated-вызовов в provisioner

- [x] 2.1 В `ProvisioningWorker` заменить `GetOption.newBuilder()` (строки 212, 269) и `DeleteOption.newBuilder()` (785) на `builder()`; проверить `mvn -pl provisioner test`
- [x] 2.2 В `ProvisioningWorker.watch()` заменить `WatchOption.newBuilder().withPrefix(prefix)` на `WatchOption.builder().isPrefix(true)`, сохранив `withRevision(fromRevision)`; проверить `mvn -pl provisioner test`
- [x] 2.3 В `ConfigProvisioner` заменить `GetOption.newBuilder()` (287, 305) и `PutOption.newBuilder()` (349) на `builder()`, а `withPrefix(bs(prefix))` в `electFor` на `isPrefix(true)` (сортировка и `withLimit(1)` без изменений); проверить `mvn -pl provisioner test`

## 3. Интеграционная проверка

- [x] 3.1 Прогнать `make test` целиком (юнит-тесты обоих модулей + promtool check config/rules/test rules) — должно быть зелёным
- [x] 3.2 На живом стенде (`make up`) убедиться, что конфигурация применяется и гейт трафика открыт: `make pool` показывает ключи конфигурации и `readiness: UP`/`ok` у всех 8 инстансов, `pool_generation` не меняется при перезапуске сервиса
- [x] 3.3 Проверить провизор на стенде: `make budget` и `make leader` в норме (лидер выборов на месте, доли инстансов сходятся с бюджетом), `ALERTS` пуст