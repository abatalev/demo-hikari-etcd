# Tasks

## 1. Путь конфигурации в сервисе

- [x] 1.1 Создать `EtcdKeyPath` (чистая функция: сборка `{root}/{service}/{group}/{instance}/hikari/`, проверка сегментов на пустоту и наличие `/`, ошибка с именем проблемного сегмента) и `EtcdKeyPathTest` (корректный путь; пустой `group`; `instance` с `/`; нормализация корня без/с хвостовым `/`). Проверка: `cd service && mvn -B -q test` зелёный.
- [x] 1.2 Заменить в `EtcdProperties` поле `prefix` на `root`/`service`/`group`/`instance` (с javadoc о происхождении каждого сегмента). Проверка: `mvn -B -q -pl . compile` проходит, ссылок на `getPrefix()`/`setPrefix()` в коде не осталось.
- [x] 1.3 Обновить `application.yml`: плейсхолдеры сегментов (`ETCD_ROOT`, `SERVICE_NAME`→`spring.application.name`, `ETCD_GROUP`→`POD_NAMESPACE`, `ETCD_INSTANCE`→`POD_NAME`). Проверка: `make up`-эквивалент старта одного инстанса снова поднимается (см. группу 5), конфиг-бандл читается без ошибок.

## 2. Источник конфигурации и готовность

- [x] 2.1 В `EtcdPoolConfigSource` убрать `prefix()`/`prefixBytes()`; путь строится один раз в конструкторе через `EtcdKeyPath` (при `enabled=true` валидация сегментов падает на старте с внятной причиной). Проверка: сборка проходит, `/api/config` отдаёт новый путь.
- [x] 2.2 Добавить в статус пути (`path`) и причину неготовности (`notReadyReason`): путь `null`, когда источник выключен; причина — отдельное поле, не перекрывающее `lastError`. Проверка: юнит-проверка сборки статуса; на стенде — `/api/config` неготового инстанса показывает причину.
- [x] 2.3 Реализовать гейт: `trafficAllowed` открывается, когда после применения в текущем наборе ключей есть распознанный ключ (`EtcdKeys.ALL`); публикуется `AvailabilityChangeEvent<ReadinessState>` (ACCEPTING_TRAFFIC); гейт не закрывается никогда. Проверка: после сида инстанс переходит в ACCEPTING_TRAFFIC, `/actuator/health/readiness` = UP.
- [x] 2.4 Пустой префикс: при снимке без единого ключа до открытия гейта — предупреждение с полным путём один раз за прогон и `notReadyReason`. Проверка: `docker compose stop etcd` на чистом старте → в логе warning с путём, `/api/config` показывает причину неготовности.
- [x] 2.5 Обновить javadoc-схему ключей в `EtcdKeys` на новый путь с сегментами; убедиться, что `EtcdKeysTest` остаётся зелёным при новой сигнатуре вызовов. Проверка: `mvn -B -q test`.

## 3. Наблюдаемость и блокировка трафика

- [x] 3.1 Добавить `HealthIndicator` (бин `poolEtcd`), отражающий состояние гейта со связным сообщением о причине; настроить группы health в `application.yml`: readiness = `readinessState,db,poolEtcd`, liveness = `ping`. Проверка: `/actuator/health/readiness` неготового инстанса DOWN с причиной, `/actuator/health/liveness` UP.
- [x] 3.2 Добавить `TrafficGateFilter`: 503 `WorkResponse{ok:false,error:...}` на `/api/work` при закрытом гейте и включённом источнике; `/api/pool`, `/api/config` и health не блокируются. Проверка: `curl -i /api/work` неготового инстанса даёт 503 и JSON-тело, `/api/pool` отвечает 200.
- [x] 3.3 Убедиться, что после открытия гейта `/api/work` снова 200 (гейт не залипает) и что инстанс, поднятый при живом etcd, сразу готов. Проверка: на стенде последовательно «остановить etcd → старт инстанса → 503 → поднять etcd → readiness UP → 200».

## 4. Нагрузчик

- [x] 4.1 В `LoadGen` добавить `STATUS_TARGET` (дефолт = `TARGET`), использовать его для `/api/pool` в репорте; обновить javadoc и `report()`-шапку. Проверка: `docker compose run loadgen` при `STATUS_TARGET=http://service-a-group-1-1:8080` печатает стабильное состояние одного инстанса.

## 5. Стенд

- [x] 5.1 Переписать `docker-compose.yml`: 8 однотипных сервисов через YAML-anchor (образ из `./service`, `mem_limit: 512m`, healthcheck на `/actuator/health/readiness`, env-сегменты `SERVICE_NAME`/`ETCD_GROUP`/`ETCD_INSTANCE`, уникальный `POOL_NAME`, порты 18081–18088), 2 балансировщика nginx (pub по 8080 и 8081), `etcd-seed` с `ETCD_INSTANCES`; убрать старый единичный `service` и `ETCD_PREFIX`. Проверка: `docker compose config` валиден, `make up` поднимает 11 контейнеров (8 сервисов + 2 nginx + прочие).
- [x] 5.2 Создать `nginx/lb.conf.template` (envsubst: `server ${BACKEND_N};`, `proxy_next_upstream error timeout http_502 http_503`, `max_fails=1 fail_timeout=5s`, `location /healthz { return 200; }`) на официальном `nginx:1.27-alpine` (образ берётся как есть, Dockerfile не нужен: шаблон монтируется в `/etc/nginx/templates/`). Проверка: `make up` — `curl localhost:8080/healthz` и `curl localhost:8081/healthz` = 200.
- [x] 5.3 Обновить `etcd/seed-config.sh`: цикл по кортежам `ETCD_INSTANCES` (`service|group|instance|port`), для каждого путь `$ETCD_ROOT/$service/$group/$instance/hikari/`, `put_if_absent` для `maximumPoolSize` и `connectionTimeoutMs`; построение пути вынести в функцию. Проверка: `make up` — `make config` показывает ключи во всех восьми путях.
- [x] 5.4 Обновить `.env.example` и создать `.env` по его образцу: `ETCD_ROOT=/config`, `ETCD_INSTANCES` из 8 кортежей с портами 18081–18088; в compose у сервисов явные сегменты совпадают с кортежами. Проверка: `make instances` печатает 8 путей вида `/config/service-a/group-1/service-a-group-1-1/hikari/` с портами.
- [x] 5.5 Обновить `Makefile`: `-include .env`; чтение `ETCD_INSTANCES`; `I=`/`S=`/`G=` для адресации; `make pool` — сводка по всем 8 инстансам, `make pool I=…` — один; `set-size`/`set-min-idle`/`unset-min-idle`/`set-conn-timeout`/`config` принимают `I=`; новые `set-group-size SIZE=… G=…`, `set-service-size SIZE=… S=…`; `up` ждёт readiness всех 8 инстансов и обоих балансировщиков с таймаутом и перечисляет неготовых; `make stress` без `DURATION_S` по-прежнему не зависает (таймаут по умолчанию). Проверка: `make up` завершается успехом; при `docker compose stop service-a-group-1-1` `make up` снова успешен; `make set-size SIZE=25 I=service-a-group-1-1` меняет один пул.
- [x] 5.6 Loadgen в compose: передать `STATUS_TARGET` (по умолчанию первый инстанс) и `DURATION_S` по-прежнему управляемый. Проверка: `docker compose ps` — нагрузчик поднят, в логах стабильный пул из `STATUS_TARGET`.

## 6. Документация

- [x] 6.1 Обновить `README.md`: новая схема ключей, топология 8×2, порты, команды `make set-size I=…`, `make pool`, `make instances`, ограничение «один путь — один инстанс», описание гейта готовности. Проверка: README собирает читателя от `make clean && make up` до ручной правки одного инстанса без шагов, которых нет в Makefile.
- [x] 6.2 Обновить `docs/mechanism.md` и `docs/api.md`: путь с сегментами, гейт и 503, `path`/`notReadyReason` в ответах, группы health. Проверка: названные поля соответствуют фактическим ответам на стенде.
- [x] 6.3 Обновить `docs/operations.md`: новые подводные камни (пустые сегменты на локальном запуске, `POOL_NAME` и `pool_sessions`, `.env` как канонический список, ломка старых ключей, лимит 512m); troubleshooting готовности через `/actuator/health/readiness` и `make pool`. Проверка: каждый документированный шаг выполним командами из Makefile.
- [x] 6.4 Обновить `AGENTS.md`: инварианты готовности (гейт не откатывается; liveness не зависит от etcd; валидация сегментов только при enabled), подводный камень про `POOL_NAME`, заменить ловушку «префикс в трёх местах» на «`ETCD_INSTANCES` в `.env` — канонический список». Проверка: новые пункты не противоречат остальному тексту.

## 7. Интеграционная проверка на живом стенде

- [x] 7.1 Раздельность конфигураций: `make set-size SIZE=25 I=service-a-group-1-1` меняет только один пул; остальные 7 инстансов в `make pool` не изменились. Проверка: вывод `make pool`.
- [x] 7.2 Балансировка по сервису: серия `/api/work` через `lb-a:8080` попадает только на инстансы `service-a` (видно по `poolName`/`total` в ответах), через `lb-b:8081` — только на `service-b`. Проверка: `curl localhost:8080/api/work` несколько раз, распределение по группе.
- [x] 7.3 Увод трафика с неготового инстанса: `docker compose stop service-b-group-2-2` → запросы через `lb-b` продолжают обслуживаться остальными тремя инстансами `service-b`, в `make pool` инстанс помечен неготовым. Проверка: `make pool` + нагрузка через `lb-b`.
- [x] 7.4 503 гейта: на чистом стенде остановить etcd, поднять один инстанс — `/api/work` даёт 503, `/api/pool` и `/api/config` работают, `/actuator/health/liveness` UP; поднять etcd, дождаться сида — инстанс сам становится готовым без рестарта. Проверка: curl-команды по шагам.
- [x] 7.5 Таймаут ожидания: при остановленном etcd `make up` завершается отказом в отведённое время и перечисляет неготовые инстансы, не зависая. Проверка: `timeout 180 make up` возвращает ненулевой код.
- [x] 7.6 Переживание правок: `make set-size SIZE=20 I=service-a-group-1-1`, затем `docker compose down && make up` (без `-v`) — значение 20 сохранено (сидер идемпотентен), стенд снова готов. Проверка: `make pool I=service-a-group-1-1`.
- [x] 7.7 Нагрузка через балансировщик: раздуть `maximumPoolSize` одного инстанса (`make set-size SIZE=30`), прогнать `make stress WORKERS=…` через `lb-a` — в отчёте видны двумодальные перцентили, отчёт читает `STATUS_TARGET`. Проверка: отчёт нагрузчика.