# Proposal

## Why

Пакеты всех трёх проектов стоят на плейсхолдере: `com.example.poolsvc`,
`com.example.provisioner`, а `groupId` во всех трёх pom'ах — `com.example`. Для стенда,
который собирают и запускают как прототип, это читается как «сгенерировано, а не
придумано»: имя `poolsvc` к тому же расходится с тем, чем проект называется в репозитории
(`service/`, `config-provisioner`), а `ru.hikari.loadgen` у нагрузчика взято из шаблона и
тоже ни с чем не совпадает.

Проект переезжает в своё пространство имён: `com.abatalev.demo.etcdhikari.{service,provisor,loadgen}`.
Ровно тот же набор пакетов, что и каталогов верхнего уровня, — искать код и читать логи
становится проще.

## What Changes

- `com.example.poolsvc` → `com.abatalev.demo.etcdhikari.service`
- `com.example.provisioner` → `com.abatalev.demo.etcdhikari.provisor`
- `ru.hikari.loadgen` → `com.abatalev.demo.etcdhikari.loadgen`
- `groupId` во всех трёх pom'ах: `com.example` → `com.abatalev.demo.etcdhikari`
- `mainClass` нагрузчика в `loadgen/pom.xml` → новый пакет

Ничего кроме имён не меняется: ни поведение, ни API, ни переменные окружения, ни набор
метрик, ни ключи в etcd, ни порты.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

Нет. Требования спек описывают поведение стенда, а не координаты пакетов: переименование
не меняет ни одного наблюдаемого свойства, поэтому дельты не заводятся (`skip_specs: true`).

Имена классов в требованиях и в `AGENTS.md` — простые имена (`ManagedPool`, `EtcdKeyPath`,
`HikariSettings`), они не меняются.

## Impact

- **Каталоги**: `service/src/**/com/example/poolsvc` → `.../com/abatalev/demo/etcdhikari/service`,
  и то же для `provisioner` (`provisor`) и `loadgen` (`ru/hikari/loadgen` → `com/abatalev/...`).
  Файлы переезжают, переименований файлов нет.
- **Текст**: `package` и `import` во всех 48 исходниках и тестах; `logging.level` в двух
  `application.yml`; `groupId` в трёх pom'ах; `mainClass` в `loadgen/pom.xml`.
- **Не меняется**: Dockerfiles (они копируют jar'ы по `artifactId`, а не по пакету),
  `docker-compose.yml`, `Makefile`, скрипты, панели Grafana, правила Prometheus, метрики.
- **Историю не переписываем**: архив `2026-10-02` и предыдущие изменения упоминают
  `ru.hikari.loadgen` — это запись о том, что было сделано тогда, и правка архива была бы
  переделкой истории.