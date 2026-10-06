# Tasks

## Готово

- [x] `provisioner-container-identity` создано (proposal.md, `.openspec.yaml` со `skip_specs: true`).
- [x] Задача 1: единый источник имени реплики в `provisioner/src/main/resources/application.yml`
  - [x] `provision.name` — цепочка `${PROV_NAME:${POD_NAME:${HOSTNAME:}}}`
  - [x] `management.metrics.tags.replica` = `${provision.name:unknown}`
  - [x] `management.opentelemetry.resource-attributes.replica` = `${provision.name:unknown}`
- [x] Задача 2: `docker-compose.yml` — схлопнуть `config-provisioner-1/2` в один `config-provisioner`
  - [x] один блок с прежними env (кроме `PROV_NAME`), healthcheck, mem_limit, depends_on
  - [x] комментарий блока переписан под масштабируемый сервис
- [x] Задача 3: `Makefile`
  - [x] `PROV_REPLICAS ?= 2` рядом с `A_G*`/`B_G*`
  - [x] `REPLICAS` получает `config-provisioner=$(PROV_REPLICAS)`
- [x] Задача 4: `.env.example`
  - [x] `PROV_REPLICAS=2` в разделе провизора
  - [x] комментарий «имена реплик в compose» заменён на `PROV_REPLICAS`
- [x] Задача 5: `scripts/wait-ready.sh` — `config-provisioner-1 config-provisioner-2` → `config-provisioner`
- [x] Задача 6: документация
  - [x] `AGENTS.md` — инвариант про `replica` и лидер-ключ, упоминания имён сервисов
  - [x] `README.md` — два блока → масштабируемый сервис
  - [x] `docs/mechanism.md` — значение лидер-ключа
  - [x] `docs/operations.md` — `PROV_NAME`, имена сервисов, `make leader`
  - [x] `docs/api.md` — признак `replica`
- [x] Задача 7: проверка на живом стенде
  - [x] пересборка образа провизора, `make up`
  - [x] два контейнера `config-provisioner-1/2`, оба живы
  - [x] `make leader` — hex-имена реплик, ровно один активный на сервис
  - [x] метка `replica` в метриках == значению лидер-ключа
  - [x] провижининг работает (бюджет, доли, гейт) — `make budget`, `make pool`
  - [x] `make check-silence`, `make check-instances` — зелёные
- [x] Задача 8: `make test` — promtool `check config`, `check rules`, `test rules`