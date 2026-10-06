# Proposal

## Why

`docker-compose.yml` дорос до 486 строк и восьми YAML-якорей: блоки групп собираются из трёх
уровней слива (`x-service` → `x-service-a`/`x-service-b` → четыре блока групп), и файл нельзя
читать сверху вниз — каждый блок требует мысленного merge с якорем. Среди якорей есть мёртвые
(`&service-a-labels`, `&service-b-labels` нигде не используются), однократный `x-etcdctl` и
перекрываемый ключ `volumes` в `x-postgres` (в обоих применениях заменяется собственным
`volumes:` блока). Хочется плоский файл: каждый сервис — самодостаточный блок без `<<:`, `&`, `*`,
а четыре инфраструктурных сервиса (etcd, otel-collector, prometheus, grafana), которые не
планируется переопределять, — константами, без `${VAR}`-параметризации.

## What Changes

- `docker-compose.yml` переписывается без YAML-якорей: все 20 сервисов — плоские самодостаточные
  блоки. В каждом из четырёх инстансных блоков env повторяется целиком (12 общих переменных +
  `SERVICE_NAME`/`DB_URL`/`ETCD_GROUP`), healthcheck и метки Traefik — в каждом блоке явно.
  Повторение принимается как плата за читаемость.
- `etcd`, `otel-collector`, `prometheus`, `grafana` — константы: хост-порты зашиваются
  (2379/2380, 4318/9464, 9090, 3000). Из `.env.example` уходят `ETCD_PORT`, `ETCD_PEER_PORT`,
  `OTLP_PORT`, `OTEL_METRICS_PORT`, `PROM_PORT`, `GRAFANA_PORT`. Makefile и `scripts/wait-ready.sh`
  не меняются: их дефолты уже равны зашитым значениям.
- `etcdctl` встраивается в сервисный блок (исчезает однократный якорь `x-etcdctl`); профиль
  `tools` сохраняется.
- Правки формулировок: в `AGENTS.md` инвариант «разделение задаётся якорями compose» теряет
  механизм и переписывается под явные описания в каждом блоке; упоминания `x-service-a`/
  `x-service-b` в `README.md` и `docs/operations.md` уточняются.
- Поведение стенда не меняется: состав сервисов, значения env, метки Traefik, healthcheck'и,
  цепочки `depends_on`, тома и механика `--scale` сохраняются. Рефакторинг проверяется
  механически: `docker compose config` до переписывания идентичен после (кроме четырёх зашитых
  хост-портов).

## Capabilities

### New Capabilities
- нет: новых возможностей не появляется.

### Modified Capabilities
- нет: поведение не меняется. Это чистый рефакторинг структуры файла и обновление документации,
  поэтому в `.openspec.yaml` изменения стоит `skip_specs: true` и дельта-спеков не будет.

## Impact

- `docker-compose.yml` — переписан без якорей, четыре сервиса константами.
- `.env.example` — минус шесть переменных портов (etcd, otel-collector, prometheus, grafana).
- `Makefile` — не меняется (дефолты портов совпадают с зашитыми; `REPLICAS`, `--scale`,
  адресация не трогаются).
- `scripts/wait-ready.sh` — не меняется (порты приходят аргументами от `make up`).
- `AGENTS.md`, `README.md`, `docs/operations.md` — правки формулировок про структуру compose
  и разделение по сервису.
- `openspec/changes/flatten-compose/.openspec.yaml` — `skip_specs: true`.