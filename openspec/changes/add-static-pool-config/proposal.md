# Proposal

## Why

В некоторых сценариях (локальная разработка, тестирование, простая установка без etcd) необходимо, чтобы сервис мог работать с HikariCP, используя статические значения из `application.yaml`, без подключения к etcd и без управления пулом через него. Сейчас архитектура жёстко завязана на etcd как единственный источник конфигурации пула: при отсутствии конфигурации из etcd пул не получает размер и трафик блокируется.

## What Changes

- Добавить режим работы `pool.etcd.enabled=false`: при отключённом etcd управление пулом не осуществляется, пул инициализируется статическими значениями из `pool.db.*`
- При статическом режиме `EtcdPoolConfigSource` не запускает воркеры (конфиг-воркер, регистрация, публикация долга), трафик разрешается сразу
- `ManagedPool` получает возможность применить начальную конфигурацию при старте без ожидания событий от etcd
- Готовность (`readiness`) и health-индикаторы, завязанные на etcd-состоянии, корректно работают в статическом режиме (etcd-индикаторы не влияют негативно при отключённом источнике)
- Поведение etcd-режима (`pool.etcd.enabled=true`) остаётся без изменений

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `pool-lifecycle`: добавляется статический режим инициализации пула без событий от etcd
- `etcd-config-source`: уточняется поведение при `enabled=false` (источник отключается, пути не валидируются, потоки не стартуют)
- `config-validation`: добавляются правила валидации статической конфигурации из `application.yaml`
- `traffic-readiness`: при статическом режиме гейт трафика открыт сразу, готовность формируется без ожидания конфигурации из etcd
- `pool-observability`: health/readiness-метрики корректно отражают статический режим

## Impact

- `service/src/main/java/com/abatalev/demo/etcdhikari/service/management/config/DbProperties` — используется как источник статических настроек при отключённом etcd
- `service/src/main/java/com/abatalev/demo/etcdhikari/service/management/pool/ManagedPool` — добавлен путь инициализации без внешних событий apply
- `service/src/main/java/com/abatalev/demo/etcdhikari/service/management/etcd/EtcdPoolConfigSource` — условный старт воркеров в зависимости от `enabled`
- `service/src/main/java/com/abatalev/demo/etcdhikari/service/management/health/*` — учёт статического режима при формировании readiness
- Конфигурация: `application.yaml` может задавать `pool.db.maximum-pool-size`, `pool.db.minimum-idle`, таймауты без etcd
