# Tasks

## 1. Подготовка референса

- [x] 1.1 Снять эталон конфигурации до правки: `docker compose config > /tmp/compose-before.yml` и `docker compose config -q` пройти без ошибок. Проверка: файл эталона непустой, конфигурация валидна.

## 2. Переписывание docker-compose.yml без якорей

- [x] 2.1 Убрать все якоря (`x-etcdctl`, `x-service`, `x-service-a`, `x-service-b`, `x-provisioner`, `x-postgres`, `x-exporter`, `x-loadgen` и их `&`-алиасы env) и развернуть содержимое в сервисные блоки: 20 сервисов плоскими самодостаточными блоками; env инстанса повторяется в каждом из четырёх блоков групп целиком (12 общих переменных + `SERVICE_NAME`/`DB_URL`/`ETCD_GROUP`), healthcheck, `depends_on` и метки Traefik — явно в каждом блоке. Проверка: `grep -E '(<<:|^x-| &| \*)' docker-compose.yml` не находит ни одного якоря.
- [x] 2.2 Встроить `etcdctl` из однократного якоря в сервисный блок (image/entrypoint/depends_on/profiles: tools) и убедиться, что состав сервисов не изменился: `docker compose config --services` до правки и после дают один и тот же список из 20 имён. Проверка: diff списков пуст.

## 3. Константы портов и .env.example

- [x] 3.1 Зашить хост-порты etcd (2379/2380), otel-collector (4318/9464), prometheus (9090) и grafana (3000) без `${VAR}`-подстановки в их блоках. Проверка: `grep -nE 'ETCD_PORT|ETCD_PEER_PORT|OTLP_PORT|OTEL_METRICS_PORT|PROM_PORT|GRAFANA_PORT' docker-compose.yml` пуст, а в `ports:` у этих сервисов стоят литеральные числа.
- [x] 3.2 Убрать `ETCD_PORT`, `ETCD_PEER_PORT`, `OTLP_PORT`, `OTEL_METRICS_PORT`, `PROM_PORT`, `GRAFANA_PORT` из `.env.example` и из комментариев к нему; Makefile и `scripts/wait-ready.sh` не править (их дефолты равны зашитым). Проверка: `grep -n 'ETCD_PORT\|OTLP_PORT\|OTEL_METRICS_PORT\|PROM_PORT\|GRAFANA_PORT' .env.example` пуст; `make -n up` и `wait-ready.sh` по-прежнему получают те же числа по умолчанию.

## 4. Документация

- [x] 4.1 В AGENTS.md переписать инвариант «Разделение задаётся якорями compose (x-service-a/x-service-b) и четырьмя блоками групп, а не блоками под каждый инстанс»: разделение по сервису теперь задано явными полями в каждом блоке групп (SERVICE_NAME, DB_URL, метки трафика), а не якорями; обновить ссылки на `x-service-a`/`x-service-b` в формулировках про метки healthcheck и состав бэкендов балансировщика. Проверка: текст AGENTS.md не утверждает, что разделение держится якорями, и не ссылается на несуществующие `x-`-блоки.
- [x] 4.2 Обновить упоминания `x-service-a`/`x-service-b` и «якоря compose» в README.md и docs/operations.md под плоскую структуру файла. Проверка: ни один док-текст не ссылается на `x-service-a`/`x-service-b` как на существующие блоки конфигурации.

## 5. Механическая сверка и живой стенд

- [x] 5.1 Изоморфность рефакторинга: `docker compose config > /tmp/compose-after.yml`; diff с эталоном `/tmp/compose-before.yml` допустим только в строках четырёх зашитых хост-портов (etcd, otel-collector, prometheus, grafana). Проверка: `diff /tmp/compose-before.yml /tmp/compose-after.yml` показывает только эти строки `ports:`.
- [x] 5.2 Живой стенд: `make up` поднимает стенд (все инстансы готовы, обе точки входа маршрутизируют), затем `make test`, `make check-silence`, `make check-instances` проходят без ошибок. Проверка: `make up` печатает «все инстансы и точки входа распределителя готовы», остальные цели завершаются с кодом 0.