# Proposal

## Why

Текущий путь конфигурации `/config/{service}/{group}/{instance}/hikari/<key>` не различает роли
сегментов читателю: неясно, где имя сервиса, где группа, где инстанс. Схема адресов меняется на
симметричную, с явным маркером перед каждым сущностным сегментом — в стиле k8s-путей
(`/apis/.../namespaces/{ns}/deployments/{name}`). Контракт поведения не меняется: спеки описывают
роли сегментов и их валидацию, а не литеральную форму пути, поэтому change несёт `skip_specs: true`.

## What Changes

Новая схема пути ключей конфигурации каждого инстанса — **BREAKING** (старые ключи не мигрируются):

```
/config/{service}/groups/{group}/instances/{instance}/hikari/<key>
     ↓
/config/services/{service}/groups/{group}/instances/{instance}/hikari/<key>
```

- Путь собирается в `EtcdKeyPath.build()`: статические маркеры `services/`, `groups/`,
  `instances/` вводятся константами рядом с `HIKARI`; валидация сегментов (непусто, без `/`)
  не меняется.
- Сидер `etcd/seed-config.sh` собирает тот же путь (источник истины — кортежи `ETCD_INSTANCES`).
- `Makefile.tuple_path` собирает тот же путь; команды `set-size`, `set-min-idle`, `config`,
  `instances` наследуют форму через неё.
- Обновляются примеры пути в javadoc/комментариях и документации (README, docs/, AGENTS.md).
- `EtcdKeyPathTest` ожидает новый образец пути.

Миграции нет: стенд поднимается заново `make clean && make up`, старые ключи в etcd остаются
мусором, который никто не читает (как в предыдущем изменении схемы).

## Capabilities

### New Capabilities

Нет. Изменение не вводит нового поведения, меняется только схема адресов ключей.

### Modified Capabilities

Нет. Требования спеки формулируют роли сегментов (сервис/группа/инстанс) и правила валидации,
но не фиксируют литеральную форму пути. Правка спек не требуется — change объявляет
`skip_specs: true`.

## Impact

- `service/src/main/java/com/example/poolsvc/etcd/EtcdKeyPath.java` — сборка и константы маркеров.
- `service/src/test/java/com/example/poolsvc/etcd/EtcdKeyPathTest.java` — ожидаемые пути.
- `etcd/seed-config.sh` — сборка префикса при сидинге.
- `Makefile` — `tuple_path`.
- Примеры пути: javadoc (`EtcdPoolConfigSource`, `EtcdKeys`), комментарий `application.yml`,
  документация (`README.md`, `docs/mechanism.md`, `docs/api.md`, `docs/operations.md`,
  `docs/experiments.md`, `AGENTS.md`).
- etcd-ключи на живом стенде не мигрируются — требуется `make clean && make up`.