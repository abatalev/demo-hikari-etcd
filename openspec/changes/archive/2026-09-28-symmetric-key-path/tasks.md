# Tasks

## 1. Java: форма пути

- [x] 1.1 Добавить в `EtcdKeyPath` константы `SERVICES = "services"`, `GROUPS = "groups"`, `INSTANCES = "instances"` рядом с `HIKARI` и пересобрать `build()` в форму `trimmedRoot + "/" + SERVICES + "/" + service + "/" + GROUPS + "/" + group + "/" + INSTANCES + "/" + instance + "/" + HIKARI + "/"`; верифицировать компиляцией модуля
- [x] 1.2 Обновить javadoc `EtcdKeyPath` с новым образцом пути `{root}/services/{service}/groups/{group}/instances/{instance}/hikari/` и проверить, что в нём нет старой формы
- [x] 1.3 Обновить `EtcdKeyPathTest`: ожидаемые пути во всех трёх кейсах построения (`/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/` и эквиваленты), а также кейсы валидации пустого сегмента и разделителя остаются зелёными; верифицировать `mvn -q test -Dtest=EtcdKeyPathTest`

## 2. Java: примеры путей в комментариях

- [x] 2.1 Обновить примеры пути в javadoc `EtcdPoolConfigSource` и `EtcdKeys` под новую форму (`/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/maximumPoolSize` и т.п.) и проверить grep'ом, что в `service/src/main` не осталось старого образца `/{service}/{group}/{instance}/hikari`
- [x] 2.2 Обновить комментарий про форму пути в `application.yml` и проверить, что в `service/src/main/resources` старая форма не упоминается

## 3. Стенд: сидер и Makefile

- [x] 3.1 Обновить `PREFIX` в `etcd/seed-config.sh` до `$ROOT/services/$svc/groups/$grp/instances/$inst/hikari/` и проверить bash-синтаксис (`bash -n`)
- [x] 3.2 Обновить `tuple_path` в `Makefile` до `$(ETCD_ROOT)/services/.../groups/.../instances/.../hikari/` и верифицировать `make instances` — выводит новые пути с маркерами для всех кортежей
- [x] 3.3 Проверить, что производные команды (`make config`, `make set-size SIZE=15`, `make pool`) работают с новым путём на остановленном стенде без ошибок (etcd не нужен для синтаксиса; фактическое применение — в п. 5)

## 4. Документация

- [x] 4.1 Обновить примеры путей в `README.md` (все места со старой формой) и проверить grep'ом, что старого образца в файле не осталось
- [x] 4.2 Обновить примеры путей в `docs/mechanism.md` и `docs/api.md` и проверить grep'ом по `docs/`
- [x] 4.3 Обновить упоминания формы пути в `docs/operations.md`, `docs/experiments.md` (если есть) и в `AGENTS.md` (раздел про путь и capability-таблицу), проверить grep'ом по корню репозитория (исключая `openspec/changes/*/` и `target/`)

## 5. Проверка на живом стенде

- [x] 5.1 Выполнить `make clean && make up` — стенд поднимается на новой схеме, все 8 инстансов готовы, оба балансировщика отвечают; верифицировать вывод `make up` и `make pool`
- [x] 5.2 Сверить пути: `make instances` (Makefile), `/api/config` инстанса по `make pool` (Java), ключи в etcd (`make config`) — все три строителя дают форму `/config/services/{service}/groups/{group}/instances/{instance}/hikari/`
- [x] 5.3 Проверить изоляцию конфигурации: `make set-size I=service-a-group-1-1 SIZE=15` меняет пул только этого инстанса; `make set-service-size S=service-b SIZE=20` меняет все четыре инстанса service-b; верифицировать по `/api/pool` инстансов
- [x] 5.4 Остановить стенд `make down` (данные сохранить) и зафиксировать итоговое состояние