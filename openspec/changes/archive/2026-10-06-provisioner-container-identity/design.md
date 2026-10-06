# Design

## Context

У сервиса идентичность контейнерная и одноисточникоая:

```yaml
# service/src/main/resources/application.yml
instance: ${ETCD_INSTANCE:${POD_NAME:${HOSTNAME:}}}
# ...
node: ${pool.etcd.instance:unknown}   # метка, тот же источник
```

У провизора сейчас имя и метка рассинхронизированы:

- `provision.name: ${PROV_NAME:}` — имя, из которого `resolveName()` делает значение
  лидер-ключа выборов (пусто → hostname → «provisioner»);
- `management.metrics.tags.replica` и `management.opentelemetry.resource-attributes.replica` —
  `replica: ${PROV_NAME:unknown}`, **не** `${provision.name}`.

На стенде `PROV_NAME` задан явно (compose, `provisioner-1/2`), поэтому оба источника равны.
Без явной переменной метка ушла бы в `unknown`, а ключ выборов — в hostname: расхождение,
которое как раз и запрещено инвариантом «метка replica = то же значение, что в ключе выборов».

## Goals / Non-Goals

**Goals**

- Идентичность реплики провизора — контейнерная, как у сервиса: `PROV_NAME → POD_NAME → HOSTNAME`.
- Один источник имени: метка `replica`, ресурс-признак и значение лидер-ключа равны всегда.
- Развёртывание — один сервис `config-provisioner` со `--scale` (число — `PROV_REPLICAS`),
  как инстансные группы; два блока схлопываются.

**Non-Goals**

- Не меняется механика выборов, бюджет, формулы распределения.
- Не меняется контракт «на стенде два экземпляра, один активный на сервис» — он сохраняется
  через `--scale 2`.
- `PROV_NAME` не удаляется как override: явно заданная переменная по-прежнему имеет приоритет
  (полезно в k8s/manual), просто compose её больше не выставляет.

## Decisions

### 1. Имя реплики — цепочка в YAML, единый источник

`provisioner/src/main/resources/application.yml`:

```yaml
provision:
  name: ${PROV_NAME:${POD_NAME:${HOSTNAME:}}}
```

и метки:

```yaml
management:
  metrics:
    tags:
      replica: ${provision.name:unknown}
  opentelemetry:
    resource-attributes:
      replica: ${provision.name:unknown}
```

`resolveName()` в Java не меняется: он получает поле `name` уже разрешённым Spring'ом
(цепочка в контейнере даёт непустой `HOSTNAME`), а пустой вклад обрабатывает сам
(hostname → «provisioner»). Метка и ключ выборов берутся из одного `${provision.name}`.

### 2. Два блока → один сервис со `--scale`

`config-provisioner-1/2` в `docker-compose.yml` схлопываются в один блок `config-provisioner`
(без `PROV_NAME`). Число реплик:

- `Makefile`: `PROV_REPLICAS ?= 2`, `REPLICAS = … service-a-group-2=$(A_G2) … config-provisioner=$(PROV_REPLICAS)`.
- `.env.example`: `PROV_REPLICAS=2` рядом с числом реплик групп.

Имена контейнеров при `--scale 2` остаются прежними (`config-provisioner-1`, `config-provisioner-2`
— суффиксы scale compose), поэтому ссылки на них в `wait-ready.sh` и документации меняются
минимально: сервис стал один — `docker compose ps config-provisioner`, `docker compose logs
config-provisioner`.

### 3. Комментарий про `PROV_NAME` из `.env.example` уходит

Раньше `.env.example` объяснял «имена реплик заданы в compose — не дублируй»; после изменения
compose имена реплик не задаёт, и комментарий переписывается на `PROV_REPLICAS`.

## Risks

| риск | митигация |
|---|---|
| Метка `replica` и значение лидер-ключа разойдутся при пустом окружении | единый источник `${provision.name}`; в контейнере `HOSTNAME` всегда задан |
| `make leader` и панели теряют читаемые `provisioner-1/2` — имена становятся hex-ID | это плата за единообразие с инстансами; `replica` остаётся различителем реплик |
| `--scale` одной группой сбросит остальные | прежний питфолл инстансных групп; масштабировать полным набором, `make up` применяет `REPLICAS` целиком |
| Число реплик провизора — параметр, который можно случайно раскачать | дефолт 2 и примечание в `.env.example` — третья реплика не даёт ничего (поллинг O(N·M)) |

## Migration Plan

1. `provisioner/src/main/resources/application.yml` — цепочка имени, метки из `${provision.name}`.
2. `docker-compose.yml` — схлопнуть два блока в `config-provisioner`, убрать `PROV_NAME`.
3. `Makefile` — `PROV_REPLICAS ?= 2`, добавить в `REPLICAS`.
4. `.env.example` — `PROV_REPLICAS=2`, правка комментария.
5. `scripts/wait-ready.sh` — `config-provisioner-1/2` → `config-provisioner`.
6. Документация: `AGENTS.md` (инвариант про `replica`, ключ выборов), `README.md`,
   `docs/mechanism.md`, `docs/operations.md`, `docs/api.md`.
7. Проверка на живом стенде: пересборка провизора, `make up`, два контейнера `config-provisioner-1/2`,
   `make leader` с hex-именами, метка `replica` == значению лидер-ключа, правила молчания.
8. `make test` — promtool `check config` / `check rules` / `test rules`.