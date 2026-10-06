# Proposal

## Why

Идентичность реплики провизора сейчас — константа compose: `PROV_NAME: provisioner-1/2`
зашит в два сервисных блока. У сервиса идентичность контейнерная
(`instance: ${ETCD_INSTANCE:${POD_NAME:${HOSTNAME:}}}`), и именно поэтому инстансные группы
разворачиваются `--scale`. Провижору контейнерная идентичность не мешает ничем (ключ выборов
привязан к аренде и уникален по hex аренды) — константа держалась только на привычке, а она
же не позволяет развернуть провизор одним масштабируемым сервисом, как инстансные группы.

## What Changes

- **Идентичность реплики — контейнерная, как у сервиса.** Имя реплики выводится из окружения
  в порядке `PROV_NAME` → `POD_NAME` → `HOSTNAME` (в контейнере `HOSTNAME` = короткий ID), явное
  `PROV_NAME` из compose уходит. `PROV_NAME` остаётся override: заданная явно переменная имеет
  приоритет, но на стенде она больше не выставляется.
- **Единый источник имени.** Метка `replica` в метриках, ресурс-признак `replica` в трассах и
  журналах и значение лидер-ключа выборов берутся из одного разрешённого имени
  (`${provision.name}`), а не из трёх разных мест. Сейчас при пустом `PROV_NAME` метка падала бы
  в `unknown`, а ключ выборов — в hostname: источник один, расхождения нет.
- **Два блока → один масштабируемый сервис.** `config-provisioner-1/2` схлопываются в один
  `config-provisioner`, число реплик задаётся `PROV_REPLICAS` (дефолт 2) в `Makefile`/`.env`,
  как инстансные группы. Имена контейнеров сохраняются (`config-provisioner-1`/`-2` — суффиксы
  `--scale`).
- **Документация.** AGENTS.md (признак `replica`, значение лидер-ключа), README.md,
  docs/mechanism.md, docs/operations.md, docs/api.md, `scripts/wait-ready.sh` — упоминания
  `config-provisioner-1/2` и «имя = `PROV_NAME`» переписываются под контейнерную идентичность
  и единый источник.

## Capabilities

### New Capabilities

- нет: новых возможностей не появляется.

### Modified Capabilities

- нет: поведенческий контракт сохраняется. На стенде по-прежнему два экземпляра провизора
  (через `--scale 2`), признак `replica` различает реплики и равен значению лидер-ключа,
  активен ровно один провизор на сервис. Меняется только происхождение идентичности и
  структура развёртывания, поэтому в `.openspec.yaml` изменения стоит `skip_specs: true`.

## Impact

- `provisioner/src/main/resources/application.yml` — имя из цепочки `PROV_NAME → POD_NAME →
  HOSTNAME`, метки `replica` из `${provision.name}`.
- `docker-compose.yml` — `config-provisioner-1/2` → один `config-provisioner`, `PROV_NAME` уходит.
- `Makefile` — `REPLICAS` получает `config-provisioner=$(PROV_REPLICAS)`; `PROV_REPLICAS ?= 2`.
- `.env.example` — `PROV_REPLICAS=2`, комментарий про имена реплик из compose убирается.
- `scripts/wait-ready.sh`, `AGENTS.md`, `README.md`, `docs/mechanism.md`, `docs/operations.md`,
  `docs/api.md` — правки формулировок.
- Правок спеки нет; `provisioner/src/main/java` — только при необходимости (проверяется при
  применении, единый источник может закрыться одним YAML).