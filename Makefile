SHELL := /bin/bash
COMPOSE ?= docker compose

# .env — канонический список инстансов и общие настройки; Makefile читает их отсюда.
-include .env

# --- адресация инстансов ---
# Кортежи ETCD_INSTANCES: service|group|instance|hostPort (через пробел).
# Дефолт ниже — только на случай отсутствующего .env; фактический список берётся из .env.
ETCD_INSTANCES ?= service-a|group-1|service-a-group-1-1|18081 service-a|group-1|service-a-group-1-2|18082 service-a|group-2|service-a-group-2-1|18083 service-a|group-2|service-a-group-2-2|18084 service-b|group-1|service-b-group-1-1|18085 service-b|group-1|service-b-group-1-2|18086 service-b|group-2|service-b-group-2-1|18087 service-b|group-2|service-b-group-2-2|18088
TUPLES := $(subst ",,$(ETCD_INSTANCES))
ETCD_ROOT ?= /config

# --- параметры команд ---
SIZE ?= 10
I ?= service-a-group-1-1
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
EXPORTER_PORT ?= 9187
WAIT_S ?= 120
TARGET ?= http://lb-a
STATUS_TARGET ?= http://$(call tuple_instance,$(word 1,$(TUPLES))):8080

# --- список целей сбора метрик ---
# Выводится из ETCD_INSTANCES: имя инстанса одновременно имя сервиса в compose, поэтому третья копия
# списка инстансов в репозитории не появляется. Файл зафиксирован (сборщик поднимается и без make),
# а make check-targets ловит расхождение.
TARGETS_FILE ?= prometheus/targets.json
METRICS_PORT ?= 8080

# --- разбор кортежа s|g|i|port ---
tuple_service  = $(word 1,$(subst |, ,$(1)))
tuple_group    = $(word 2,$(subst |, ,$(1)))
tuple_instance = $(word 3,$(subst |, ,$(1)))
tuple_port     = $(word 4,$(subst |, ,$(1)))
tuple_path     = $(ETCD_ROOT)/services/$(call tuple_service,$(1))/groups/$(call tuple_group,$(1))/instances/$(call tuple_instance,$(1))/hikari/

# кортеж по имени инстанса (I=...)
tuple_by_I = $(foreach t,$(TUPLES),$(if $(filter $(I),$(call tuple_instance,$(t))),$(t)))
# кортежи по сервису (S=...) / группе (G=...)
tuples_by_S = $(foreach t,$(TUPLES),$(if $(filter $(S),$(call tuple_service,$(t))),$(t)))
tuples_by_G = $(foreach t,$(TUPLES),$(if $(filter $(G),$(call tuple_group,$(t))),$(t)))
# кортежи по сервису, заданному аргументом (для циклов по переменной-аргументу)
tuples_by_serv = $(foreach t,$(TUPLES),$(if $(filter $(1),$(call tuple_service,$(t))),$(t)))

# Список application_name пулов стенда в виде SQL IN ('a','b'): application_name = POOL_NAME,
# и посторонние сессии (psql, нагрузчик) в сумму флота попадать не должны.
pool_names_sql = $(subst $(space),$(comma),$(foreach t,$(TUPLES),'$(call tuple_instance,$(t))'))
comma := ,
space := $(empty) $(empty)

# Имена сервисов: из S=... либо все сервисы .env по порядку первого появления. Кортежи в shell
# кавычим: иначе `|` внутри кортежа разрежется в пайп и for развалится.
service_names = $(if $(S),$(S),$(shell for t in $(foreach t,$(TUPLES),'$(t)'); do echo $${t%%|*}; done | awk '!seen[$$0]++'))

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

.PHONY: instances
instances: ## расклад инстансов: имя, порт, путь конфигурации
	@$(foreach t,$(TUPLES),printf "%-28s %-6s %s\n" "$(call tuple_instance,$(t))" "$(call tuple_port,$(t))" "$(call tuple_path,$(t))";)

.PHONY: up
up: prometheus-targets ## поднять весь стенд и дождаться готовности всех инстансов и балансировщиков
	$(COMPOSE) up -d --build
	@echo "--- ждём готовности всех инстансов и балансировщиков (до $(WAIT_S)с) ---"
	@./scripts/wait-ready.sh "$(TUPLES)" $(LB_A_PORT) $(LB_B_PORT) $(WAIT_S) \
		$(PROM_PORT) $(GRAFANA_PORT) $(EXPORTER_PORT)
	@$(MAKE) --no-print-directory pool
	# Состояние целей — только сообщение: недоступность наблюдения не должна ронять make up
	-@$(MAKE) --no-print-directory targets

.PHONY: down
down: ## остановить стенд (с данными)
	$(COMPOSE) down

.PHONY: clean
clean: ## остановить стенд и удалить тома с данными
	$(COMPOSE) down -v

.PHONY: ps
ps: ## состояние контейнеров
	$(COMPOSE) ps

.PHONY: logs
logs: ## логи инстанса: make logs P=service-a-group-1-1
	$(COMPOSE) logs -f --tail=200 $(P)

.PHONY: load-logs
load-logs: ## логи нагрузчика
	$(COMPOSE) logs -f --tail=100 loadgen

.PHONY: stress
stress: ## разовый прогон нагрузки через lb-a (DURATION_S по умолчанию 20с): WORKERS=32 WORK_MS=200 make stress
	$(COMPOSE) run --rm --no-deps \
		-e TARGET=$(TARGET) -e STATUS_TARGET=$(STATUS_TARGET) \
		-e WORKERS=$(WORKERS) -e WORK_MS=$(WORK_MS) -e REPORT_MS=$(REPORT_MS) \
		-e DURATION_S=$(if $(filter-out 0,$(DURATION_S)),$(DURATION_S),20) loadgen

# --- ручные правки конфигурации в etcd ---
# Адресация: I=имя инстанса (один), S=имя сервиса, G=имя группы.
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
set-size: ## РАЗМЕР ПУЛА ОВЕРРАЙДОМ: make set-size SIZE=25 [I=имя] — живёт до пересчёта бюджета
	@$(if $(call tuple_by_I,$(I)),,@echo "не найден инстанс I=$(I)"; exit 1)
	@echo "etcd: $(call tuple_path,$(call tuple_by_I,$(I)))maximumPoolSize = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(call tuple_by_I,$(I)))maximumPoolSize" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory pool I=$(I)

.PHONY: set-service-size
set-service-size: ## оверрайд размера всех инстансов сервиса: SIZE=25 S=service-a — живёт до пересчёта
	@$(if $(S),,@echo "укажи S=имя сервиса"; exit 1)
	@$(foreach t,$(call tuples_by_S),$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(t))maximumPoolSize" "$(SIZE)";)
	@$(MAKE) --no-print-directory pool

.PHONY: set-group-size
set-group-size: ## оверрайд размера всех инстансов группы: SIZE=25 G=group-1 — живёт до пересчёта
	@$(if $(G),,@echo "укажи G=имя группа"; exit 1)
	@$(foreach t,$(call tuples_by_G),$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(t))maximumPoolSize" "$(SIZE)";)
	@$(MAKE) --no-print-directory pool

.PHONY: set-min-idle
set-min-idle: ## зафиксировать minimumIdle инстанса: make set-min-idle SIZE=3 [I=имя]
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(call tuple_by_I,$(I)))minimumIdle" "$(SIZE)"

.PHONY: unset-min-idle
unset-min-idle: ## убрать minimumIdle инстанса (снова follow за maximumPoolSize): [I=имя]
	@$(COMPOSE) run --rm --no-deps etcdctl del "$(call tuple_path,$(call tuple_by_I,$(I)))minimumIdle"

.PHONY: set-conn-timeout
set-conn-timeout: ## connectionTimeoutMs инстанса: make set-conn-timeout SIZE=1000 [I=имя]
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(call tuple_by_I,$(I)))connectionTimeoutMs" "$(SIZE)"

.PHONY: config
config: ## ключи инстанса из etcd: make config [I=имя]
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(call tuple_path,$(call tuple_by_I,$(I)))"

.PHONY: budget
budget: ## бюджет сервисов: N, m, R, маркеры групп, живые инстансы, сумма долей (make budget [S=сервис])
	@$(foreach s,$(call service_names),\
		printf "== %s ==\n" "$(s)"; \
		$(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/" -w json \
			| python3 scripts/budget.py '$(call tuples_by_serv,$(s))' "$(ETCD_ROOT)/"; )

.PHONY: registrations
registrations: ## узлы регистрации инстансов в etcd: создаются при старте, исчезают при остановке
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(ETCD_ROOT)/services/" --keys-only \
		| grep -E '/instances/[^/]+/?$$'

.PHONY: leader
leader: ## кто ведёт каждый сервис (лидер выборов провизора, наименьший create_revision)
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(ETCD_ROOT)/provisioner/leader/" -w json \
		| python3 scripts/leaders.py

.PHONY: pool
pool: ## сводка по всем инстансам; make pool I=имя — детально один
	@$(if $(filter command line,$(origin I)),curl -fsS http://localhost:$(call tuple_port,$(call tuple_by_I,$(I)))/api/pool | python3 -m json.tool,python3 scripts/pool-all.py '$(TUPLES)')

.PHONY: work
work: ## один запрос через lb-a
	@curl -fsS "http://localhost:$(LB_A_PORT)/api/work?ms=100" | python3 -m json.tool

.PHONY: psql
psql: ## зайти в postgres
	@$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo}

.PHONY: sessions
sessions: ## сессии postgres по application_name (сколько коннектов держит каждый пул)
	@$(COMPOSE) exec -T postgres psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo} \
		-c "SELECT * FROM pool_sessions ORDER BY application_name"

.PHONY: fleet-sessions
fleet-sessions: ## держит ли флот бюджет: фактические сессии в БД + потолки и публикации из etcd
	@printf "== фактически держит флот (postgres) ==\n"; \
		$(COMPOSE) exec -T postgres psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo} \
			-c "SELECT sum(sessions) AS held_by_fleet, count(*) AS pools FROM pool_sessions \
				WHERE application_name IN ($(call pool_names_sql))"; \
	printf "== потолки и публикации (etcd) ==\n"; \
		$(COMPOSE) run --rm --no-deps -T etcdctl get --prefix "$(ETCD_ROOT)/services/" -w json \
			| python3 scripts/fleet-sessions.py "$(ETCD_ROOT)/"

.PHONY: service-restart
service-restart: ## перезапустить инстанс: make service-restart P=service-a-group-1-1
	$(COMPOSE) restart $(P)

.PHONY: prometheus-targets
prometheus-targets: ## перегенерировать список целей сбора метрик из ETCD_INSTANCES
	@mkdir -p $(dir $(TARGETS_FILE))
	@python3 scripts/targets.py '$(TUPLES)' '$(METRICS_PORT)' '$(TARGETS_FILE)'
	@echo "цели сбора: $(words $(TUPLES)) инстанс(ов) -> $(TARGETS_FILE)"

.PHONY: check-targets
check-targets: ## сверить зафиксированный список целей сбора с ETCD_INSTANCES (входит в make test)
	@mkdir -p $(dir $(TARGETS_FILE))
	@python3 scripts/targets.py '$(TUPLES)' '$(METRICS_PORT)' '$(TARGETS_FILE).tmp'
	@if diff -u '$(TARGETS_FILE)' '$(TARGETS_FILE).tmp' > /tmp/targets.diff; then \
		rm -f '$(TARGETS_FILE).tmp'; \
		echo "список целей совпадает с ETCD_INSTANCES ($(words $(TUPLES)))"; \
	else \
		echo "список целей разошёлся с ETCD_INSTANCES:"; cat /tmp/targets.diff; \
		echo "перегенерируй: make prometheus-targets"; rm -f '$(TARGETS_FILE).tmp'; exit 1; \
	fi

.PHONY: targets
targets: ## состояние целей сбора метрик (нужен поднятый сборщик)
	@curl -fsS --max-time 10 "http://localhost:$(PROM_PORT)/api/v1/targets?state=active" \
		| python3 scripts/targets-state.py || \
		{ echo "сборщик метрик на порту $(PROM_PORT) не отвечает: docker compose ps prometheus"; \
		  docker compose logs --tail=30 prometheus; exit 1; }

.PHONY: test
test: ## юнит-тесты сервиса и провизора, проверка целей сбора, конфигурации и правил
	cd service && mvn -B -q test
	cd provisioner && mvn -B -q test
	@$(MAKE) --no-print-directory check-targets
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus check rules /etc/prometheus/rules/stand.yml
	# Правило обязано и молчать на норме, и срабатывать на нарушении: синтаксис этого не проверяет.
	$(COMPOSE) run --rm --no-deps -T --entrypoint promtool prometheus test rules /etc/prometheus/rules/stand.test.yml
