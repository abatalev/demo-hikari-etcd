SHELL := /bin/bash
COMPOSE ?= docker compose

# .env — общие настройки и масштабы групп; Makefile читает их отсюда.
-include .env

# --- состав флота ---
# Число реплик группы задаётся через docker compose --scale из переменных ниже (дефолты на случай
# отсутствующего .env, правятся в .env). Списка инстансов в конфигурации больше нет: что развёрнуто —
# объявляет docker compose ps (make instances), а идентичность инстанса приходит из окружения
# (HOSTNAME в контейнере = короткий ID, он же POOL_NAME и имя узла регистрации в etcd).
A_G1 ?= 2
A_G2 ?= 2
B_G1 ?= 2
B_G2 ?= 2
# Число реплик провизора (как у инстансных групп, дефолт 2 = лидер + резерв; больше не нужно —
# третья реплика ничего не добавляет, выборы — поллинг O(N·M)).
PROV_REPLICAS ?= 2
FLEET_GROUPS = service-a-group-1 service-a-group-2 service-b-group-1 service-b-group-2
REPLICAS = service-a-group-1=$(A_G1) service-a-group-2=$(A_G2) service-b-group-1=$(B_G1) service-b-group-2=$(B_G2) config-provisioner=$(PROV_REPLICAS)
# аргументы --scale для docker compose up
scale_args = $(foreach r,$(REPLICAS),--scale $(r))
ETCD_ROOT ?= /config

# --- параметры команд ---
SIZE ?= 10
I ?=
S ?=
G ?=
P ?=
WORKERS ?= 4
WORK_MS ?= 25
REPORT_MS ?= 2000
DURATION_S ?= 0
LB_A_PORT ?= 8080
LB_B_PORT ?= 8081
PROM_PORT ?= 9090
GRAFANA_PORT ?= 3000
EXPORTER_PORT_A ?= 9187
EXPORTER_PORT_B ?= 9188
OTEL_METRICS_PORT ?= 9464
TEMPO_PORT ?= 3200
LOKI_PORT ?= 3100
WAIT_S ?= 120
# Нагрузка адресуется сервисом, но отдельной переменной: S= обязателен в командах etcd (там
# сервис выбирает, что править, и молчаливый выбор опасен), а дефолтный S= убрал бы эту проверку.
LOAD_S ?= service-a
# Распределитель один, точек входа две: 80 отдаёт только LOAD_S, 81 — только второй сервис.
# Порт назван здесь, а не в TARGET по умолчанию для нагрузчика: TARGET адресуется внутри сети
# compose, где у контейнера lb свои номера, и путать их с портами хоста нельзя.
LB_TARGET_PORT = $(if $(filter service-a,$(LOAD_S)),80,81)
LB_HOST_PORT = $(if $(filter service-a,$(LOAD_S)),$(LB_A_PORT),$(LB_B_PORT))
LOAD_LB ?= lb
TARGET ?= http://$(LOAD_LB):$(LB_TARGET_PORT)

# --- правила молчания инстанса ---
# Список целей сбора удалён вместе с переходом на отправку: целей у сборщика теперь одна — точка
# приёма, и молчание отдельного инстанса она не выражает. Вместо неё из состава docker compose ps
# выводится правило отсутствия ряда на каждый инстанс (scripts/silence.py). Файл зафиксирован
# (сборщик поднимается и без make), а make check-silence ловит расхождение. Рядом лежит файл теста
# правил — его тоже генерирует silence.py, а проверяет make test (promtool test rules).
SILENCE_FILE ?= prometheus/rules/instances.yml
SILENCE_TEST_FILE ?= prometheus/rules/instances.test.yml

# --- адресация базы данных по сервису ---
# База на сервис, и имя контейнера выводится из имени сервиса: service-a -> postgres-a. Третьей
# копии списка не появляется, как и с инстансами (состав выводится из docker compose ps).
service_suffix = $(patsubst service-%,%,$(1))
db_container   = postgres-$(call service_suffix,$(1))
exporter_container = postgres-exporter-$(call service_suffix,$(1))
loadgen_container  = loadgen-$(call service_suffix,$(1))

# --- адресация инстанса в etcd ---
# Имена инстансов приходят из окружения (короткий ID контейнера), поэтому ручные правки адресуются
# живыми узлами регистрации: I=имя узла, а путь конфигурации выводится из снимка ключей etcd, а не
# из перечня. require-I требует, чтобы узел с таким именем реально существовал, иначе путь не из
# чего вывести (и опечатка молча попала бы не туда).
path_of_I = $(shell $(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" --keys-only 2>/dev/null \
	| grep -E "/instances/$(I)(/|$$)" | head -n1 | sed 's#/instances/$(I).*#/instances/$(I)/hikari/#')

# Команды, работающие с базой, требуют S=...: баз несколько, и угадывать нечего. Молча смотренная
# не та база опаснее падения — сводка «флот 100» из двух баз читалась бы как «флот 200 в одной».
# Это отдельная цель-guard, а не $(if) внутри рецепта: пустой $(if) в shell даёт синтаксическую
# ошибку вместо внятного отказа. Список сервисов выводится из живых узлов регистрации (etcd).
.PHONY: require-S
require-S:
	@if [ -z "$(S)" ]; then \
		echo "укажи S=имя сервиса (база на сервис; живые сервисы: make services)"; exit 1; \
	fi; \
	services=$$($(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" -w json \
		| python3 scripts/services.py "$(ETCD_ROOT)/" | tr '\n' ' ') || exit 1; \
	if ! echo " $$services " | grep -q " $(S) "; then \
		echo "неизвестный сервис S=$(S) (по узлам регистрации: $$services)"; exit 1; \
	fi

.PHONY: require-I
require-I:
	@if [ -z "$(I)" ]; then \
		echo "укажи I=имя инстанса (живой состав: make instances)"; exit 1; \
	fi
	@if ! $(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" --keys-only 2>/dev/null \
		| grep -qE "/instances/$(I)(/|$$)"; then \
		echo "не найден инстанс I=$(I) в узлах регистрации (живой состав: make instances)"; exit 1; \
	fi

# Проверка аргумента SIZE до записи в etcd: не число или не положительное — отказ.
require_positive_int = $(if $(filter-out 0,$(shell test $(2) -gt 0 2>/dev/null && echo ok)),,\
	@echo "$(1) должен быть положительным числом, а не '$(2)'"; exit 1)
# Как require_positive_int, но 0 допустим (резерв неактивного флота R=0 = холод).
require_size = $(if $(filter-out 0,$(shell test $(2) -ge 0 2>/dev/null && echo ok)),,\
	@echo "$(1) должен быть числом (0 допустим), а не '$(2)'"; exit 1)

# Текущее значение сервисного ключа бюджета из etcd; при недоступном etcd — «ok» (проверку пропускаем:
# запись всё равно не пройдёт, а решение провижёра — fail-closed с WARN в его логах).
current_min = $(or $(shell $(COMPOSE) run --rm --no-deps -T etcdctl \
	get "$(ETCD_ROOT)/services/$(1)/activeMinConnections" 2>/dev/null | sed -n '2p'),ok)
current_max = $(or $(shell $(COMPOSE) run --rm --no-deps -T etcdctl \
	get "$(ETCD_ROOT)/services/$(1)/activeMaxConnections" 2>/dev/null | sed -n '2p'),ok)
conflicts_with_min = $(shell test $(1) -le $(call current_min,$(2)) 2>/dev/null && echo yes)
exceeds_budget = $(shell test $(1) -gt $(call current_max,$(2)) 2>/dev/null && echo yes)

.DEFAULT_GOAL := help

.PHONY: help
help: ## список целей
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## собрать образы сервиса, провизора, нагрузчика и etcdctl
	$(COMPOSE) build

.PHONY: services
services: ## живые сервисы по узлам регистрации etcd
	@$(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" -w json \
		| python3 scripts/services.py "$(ETCD_ROOT)/"

.PHONY: instances
instances: ## расклад инстансов: service|group|instance из docker compose ps + путь конфигурации
	@python3 scripts/fleet.py | awk -F'|' '{printf "%-14s %-16s %s\n", $$3, $$1"/"$$2, "$(ETCD_ROOT)/services/"$$1"/groups/"$$2"/instances/"$$3"/hikari/"}'

.PHONY: up
up: ## поднять весь стенд (реплики групп из A_G1/A_G2/B_G1/B_G2) и дождаться готовности
	$(COMPOSE) up -d --build $(call scale_args)
	@# Правила молчания генерируются ПОСЛЕ поднятия: их состав объявляет docker compose ps, и на
	@# пустом стенде генерировать нечего. Перезагрузка правил без рестарта сборщика — SIGHUP
	@# (/-/reload, --web.enable-lifecycle в compose): недоступный сборщик не роняет подъём.
	@$(MAKE) --no-print-directory silence-rules
	-@curl -fsS -X POST "http://localhost:$(PROM_PORT)/-/reload" >/dev/null 2>&1 \
		|| echo "не перечитал правила сборщик (ещё не поднялся?) — прочитает при старте"
	@echo "--- ждём готовности всех инстансов и распределителя (до $(WAIT_S)с) ---"
	@./scripts/wait-ready.sh $(LB_A_PORT) $(LB_B_PORT) $(WAIT_S) \
		$(PROM_PORT) $(GRAFANA_PORT) $(EXPORTER_PORT_A) $(EXPORTER_PORT_B) \
		$(OTEL_METRICS_PORT) $(TEMPO_PORT) $(LOKI_PORT)
	@$(MAKE) --no-print-directory pool
	# Состав в наблюдении — только сообщение: недоступность наблюдения не должна ронять make up
	-@$(MAKE) --no-print-directory check-instances
	-@$(MAKE) --no-print-directory targets

.PHONY: down
down: ## остановить стенд (с данными); --remove-orphans: контейнеры, выведенные из состава, тоже снимаются
	$(COMPOSE) down --remove-orphans

.PHONY: clean
clean: ## остановить стенд и удалить тома с данными
	$(COMPOSE) down -v --remove-orphans

.PHONY: ps
ps: ## состояние контейнеров
	$(COMPOSE) ps

.PHONY: logs
logs: ## логи: make logs P=<имя контейнера> (hex-ID инстанса или имя контейнера compose)
	@test -n "$(P)" || { echo "укажи P=hex-ID инстанса (make instances)"; exit 1; }
	docker logs -f --tail=200 $(P)

.PHONY: load-logs
load-logs: ## логи нагрузчика сервиса: make load-logs LOAD_S=service-b
	$(COMPOSE) logs -f --tail=100 $(call loadgen_container,$(LOAD_S))

.PHONY: stress
stress: ## разовый прогон нагрузки на сервис (DURATION_S по умолчанию 20с): LOAD_S=service-b WORKERS=32 WORK_MS=200 make stress
	@echo "нагрузка на $(LOAD_S) через $(LOAD_LB):$(LB_TARGET_PORT)"
	$(COMPOSE) run --rm --no-deps \
		-e TARGET=$(TARGET) \
		-e WORKERS=$(WORKERS) -e WORK_MS=$(WORK_MS) -e REPORT_MS=$(REPORT_MS) \
		-e DURATION_S=$(if $(filter-out 0,$(DURATION_S)),$(DURATION_S),20) \
		$(call loadgen_container,$(LOAD_S))

# --- ручные правки конфигурации в etcd ---
# Адресация: I=имя инстанса (hex-ID контейнера, один), S=имя сервиса, G=имя группы.
# Размер пула задаётся бюджетом сервиса (set-active-max-connections): провижёр делит его между
# живыми инстансами активных групп, а группы с флагом неактивности (set-group-active ACTIVE=false,
# глобальный флот {root}/groups/) сжимаются до резерва set-inactive-max-connections.
# set-size/set-service-size/set-group-size пишут maximumPoolSize напрямую и живут до ближайшего
# пересчёта: это временный оверайд, а не способ задать размер пула (ручной 0 провижёр с трением
# перезапишет/оставит службе, см. docs/mechanism.md).

.PHONY: set-active-max-connections
set-active-max-connections: ## бюджет соединений сервиса N: make set-active-max-connections SIZE=100 S=service-a
	@$(if $(S),,@echo "укажи S=имя сервиса"; exit 1)
	@$(call require_positive_int,SIZE,$(SIZE))
	@$(if $(call conflicts_with_min,$(SIZE),$(S)),@echo "минимум сервиса ($(call current_min,$(S))) выше бюджета ($(SIZE)):"; \
		echo "часть инстансов останется без доли и не будет обслуживать трафик (503)"; exit 1)
	@echo "etcd: $(ETCD_ROOT)/services/$(S)/activeMaxConnections = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(ETCD_ROOT)/services/$(S)/activeMaxConnections" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory budget
	@$(MAKE) --no-print-directory pool

.PHONY: set-active-min-connections
set-active-min-connections: ## минимальная доля активного инстанса: make set-active-min-connections SIZE=15 S=service-a
	@$(if $(S),,@echo "укажи S=имя сервиса"; exit 1)
	@$(call require_positive_int,SIZE,$(SIZE))
	@$(if $(call exceeds_budget,$(SIZE),$(S)),@echo "минимум ($(SIZE)) выше бюджета сервиса ($(call current_max,$(S))):"; \
		echo "провижёр не выполнит распределение и оставит конфигурацию как есть (WARN в его логах)"; exit 1)
	@echo "etcd: $(ETCD_ROOT)/services/$(S)/activeMinConnections = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(ETCD_ROOT)/services/$(S)/activeMinConnections" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory budget
	@$(MAKE) --no-print-directory pool

.PHONY: set-inactive-max-connections
set-inactive-max-connections: ## резерв неактивного флота R: make set-inactive-max-connections SIZE=0 S=service-a (0 = холод)
	@$(if $(S),,@echo "укажи S=имя сервиса"; exit 1)
	@$(call require_size,SIZE,$(SIZE))
	@echo "etcd: $(ETCD_ROOT)/services/$(S)/inactiveMaxConnections = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(ETCD_ROOT)/services/$(S)/inactiveMaxConnections" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory budget
	@$(MAKE) --no-print-directory pool

.PHONY: set-group-active
set-group-active: ## флаг активности группы (глобальный флот): make set-group-active G=group-2 ACTIVE=false
	@$(if $(G),,@echo "укажи G=имя группы"; exit 1)
	@$(if $(filter true false,$(ACTIVE)),,@echo "ACTIVE должен быть true или false"; exit 1)
	@echo "etcd: $(ETCD_ROOT)/groups/$(G)/active = $(ACTIVE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(ETCD_ROOT)/groups/$(G)/active" "$(ACTIVE)"
	@sleep 1
	@$(MAKE) --no-print-directory budget
	@$(MAKE) --no-print-directory pool

.PHONY: set-size
set-size: require-I ## РАЗМЕР ПУЛА ОВЕРРАЙДОМ: make set-size SIZE=25 I=<hex> — живёт до пересчёта бюджета
	@echo "etcd: $(call path_of_I)maximumPoolSize = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(call path_of_I)maximumPoolSize" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory pool

.PHONY: set-service-size
set-service-size: require-S ## оверрайд размера всех провиженных инстансов сервиса: SIZE=25 S=service-a — живёт до пересчёта
	@paths=$$($(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/$(S)/" --keys-only \
		| sed -n 's#\(.*/instances/[^/]*\)/hikari/.*#\1/hikari/#p' | sort -u); \
	if [ -z "$$paths" ]; then echo "нет провиженных инстансов сервиса $(S) (make registrations, make instances)"; exit 1; fi; \
	for p in $$paths; do \
		$(COMPOSE) run --rm --no-deps etcdctl put "$${p}maximumPoolSize" "$(SIZE)"; \
	done
	@$(MAKE) --no-print-directory pool

.PHONY: set-group-size
set-group-size: ## оверрайд размера всех провиженных инстансов группы: SIZE=25 G=group-1 — живёт до пересчёта
	@$(if $(G),,@echo "укажи G=имя группы"; exit 1)
	@paths=$$($(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" --keys-only \
		| grep "/groups/$(G)/instances/" | sed -n 's#\(.*/instances/[^/]*\)/hikari/.*#\1/hikari/#p' | sort -u); \
	if [ -z "$$paths" ]; then echo "нет провиженных инстансов группы $(G) (make registrations, make instances)"; exit 1; fi; \
	for p in $$paths; do \
		$(COMPOSE) run --rm --no-deps etcdctl put "$${p}maximumPoolSize" "$(SIZE)"; \
	done
	@$(MAKE) --no-print-directory pool

.PHONY: set-min-idle
set-min-idle: require-I ## зафиксировать minimumIdle инстанса: make set-min-idle SIZE=3 I=<hex>
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(call path_of_I)minimumIdle" "$(SIZE)"

.PHONY: unset-min-idle
unset-min-idle: require-I ## убрать minimumIdle инстанса (снова follow за maximumPoolSize): I=<hex>
	@$(COMPOSE) run --rm --no-deps etcdctl del "$(call path_of_I)minimumIdle"

.PHONY: config
config: require-I ## ключи инстанса из etcd: make config I=<hex>
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(call path_of_I)"

.PHONY: budget
budget: ## бюджет сервисов: N, m, R, маркеры групп, живые инстансы, сумма долей (make budget [S=сервис])
	@$(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/" -w json \
		| python3 scripts/budget.py "$(ETCD_ROOT)/" "$(S)"

.PHONY: registrations
registrations: ## узлы регистрации инстансов в etcd: создаются при старте, исчезают при остановке
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(ETCD_ROOT)/services/" --keys-only \
		| grep -E '/instances/[^/]+/?$$'

.PHONY: leader
leader: ## кто ведёт каждый сервис (лидер выборов провизора, наименьший create_revision)
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(ETCD_ROOT)/provisioner/leader/" -w json \
		| python3 scripts/leaders.py

.PHONY: pool
pool: ## сводка по всем инстансам: готовность, пул из метрик, путь конфигурации и ключи в etcd
	@$(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" -w json \
		| python3 scripts/pool-all.py "$(ETCD_ROOT)/" 'http://127.0.0.1:$(PROM_PORT)'

.PHONY: work
work: ## один запрос через балансировщик сервиса: make work LOAD_S=service-b
	@curl -fsS "http://localhost:$(LB_HOST_PORT)/api/work?ms=100" \
		| python3 -m json.tool

.PHONY: psql
psql: require-S ## зайти в базу сервиса: make psql S=service-a
	@echo "база: $(call db_container,$(S)) (сервис $(S))"
	@$(COMPOSE) exec $(call db_container,$(S)) psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo}

.PHONY: sessions
sessions: require-S ## сессии базы сервиса по application_name: make sessions S=service-a
	@echo "база: $(call db_container,$(S)) (сервис $(S))"
	@$(COMPOSE) exec -T $(call db_container,$(S)) psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo} \
		-c "SELECT * FROM pool_sessions ORDER BY application_name"

.PHONY: fleet-sessions
fleet-sessions: require-S ## держит ли флот бюджет: make fleet-sessions S=service-a — сводка по его базе
	@nodes=$$($(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" -w json \
		| python3 scripts/reg-nodes.py "$(ETCD_ROOT)/" "$(S)"); \
	if [ -z "$$nodes" ]; then echo "нет зарегистрированных узлов сервиса $(S) (etcd недоступен?)"; exit 1; fi; \
	inlist=$$(printf '%s\n' "$$nodes" | awk '{printf "\x27%s\x27\n", $$0}' | paste -sd, -); \
	printf "== фактически держит флот (база %s, сервис %s) ==\n" "$(call db_container,$(S))" "$(S)"; \
	$(COMPOSE) exec -T $(call db_container,$(S)) psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo} \
		-c "SELECT sum(sessions) AS held_by_fleet, count(*) AS pools FROM pool_sessions WHERE application_name = ANY (ARRAY[$$inlist])"
	@printf "== потолки и публикации (etcd) ==\n"
	@$(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" -w json \
		| python3 scripts/fleet-sessions.py "$(ETCD_ROOT)/" "$(S)"

.PHONY: service-restart
service-restart: ## перезапустить инстанс: make service-restart P=<hex-ID> (или имя контейнера compose)
	@test -n "$(P)" || { echo "укажи P=hex-ID инстанса (make instances)"; exit 1; }
	docker restart $(P)

.PHONY: silence-rules
silence-rules: ## перегенерировать правила молчания инстанса из состава docker compose ps
	@mkdir -p $(dir $(SILENCE_FILE))
	@python3 scripts/silence.py '$(SILENCE_FILE)' '$(SILENCE_TEST_FILE)'
	@echo "правила молчания -> $(SILENCE_FILE), $(SILENCE_TEST_FILE)"

.PHONY: check-silence
check-silence: ## сверить зафиксированные правила молчания с составом docker compose ps (входит в make test)
	@mkdir -p $(dir $(SILENCE_FILE))
	@python3 scripts/silence.py '/tmp/instances.silence.yml' '/tmp/instances.test.silence.yml'
	@if diff -u '$(SILENCE_FILE)' '/tmp/instances.silence.yml' > /tmp/silence.diff && \
		diff -u '$(SILENCE_TEST_FILE)' '/tmp/instances.test.silence.yml' > /tmp/silence-test.diff; then \
		rm -f '/tmp/instances.silence.yml' '/tmp/instances.test.silence.yml'; \
		echo "правила молчания совпадают с составом docker compose ps"; \
	else \
		echo "правила молчания разошлись с составом:"; cat /tmp/silence.diff /tmp/silence-test.diff; \
		echo "перегенерируй: make silence-rules"; rm -f '/tmp/instances.silence.yml' '/tmp/instances.test.silence.yml'; exit 1; \
	fi

.PHONY: check-instances
check-instances: ## живая сверка состава: у каждого инстанса docker compose ps есть ряды в сборщике
	@python3 scripts/check-instances.py 'http://127.0.0.1:$(PROM_PORT)'

.PHONY: targets
targets: ## состояние целей сбора метрик (нужен поднятый сборщик)
	@curl -fsS --max-time 10 "http://localhost:$(PROM_PORT)/api/v1/targets?state=active" \
		| python3 scripts/targets-state.py || \
		{ echo "сборщик метрик на порту $(PROM_PORT) не отвечает: docker compose ps prometheus"; \
		  docker compose logs --tail=30 prometheus; exit 1; }

.PHONY: door-config
door-config: ## проверить конфигурацию двери сигналов (входит в make test)
	@# Собственная проверка точки приёма: она разбирает конфигурацию и печатает использованные
	@# компоненты. Ошибку в конфигурации она ловит на старте, и без этой проверки стенд
	@# поднимался бы с молчащим отказом двери. У образа нет оболочки, поэтому --entrypoint.
	$(COMPOSE) run --rm --no-deps -T --entrypoint /otelcol-contrib otel-collector \
		validate --config=/etc/otel/collector.yaml

.PHONY: test
test: ## юнит-тесты сервиса и провизора, проверка правил, конфигурации и состава (нужен поднятый стенд)
	cd service && mvn -B -q test
	cd provisioner && mvn -B -q test
	@$(MAKE) --no-print-directory check-silence
	@$(MAKE) --no-print-directory check-instances
	@$(MAKE) --no-print-directory door-config
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus check rules /etc/prometheus/rules/stand.yml
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus check rules /etc/prometheus/rules/instances.yml
	# Правило обязано и молчать на норме, и срабатывать на нарушении: синтаксис этого не проверяет.
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus test rules /etc/prometheus/rules/stand.test.yml
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus test rules /etc/prometheus/rules/instances.test.yml