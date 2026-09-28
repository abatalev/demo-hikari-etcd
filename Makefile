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
WAIT_S ?= 120
TARGET ?= http://lb-a
STATUS_TARGET ?= http://$(call tuple_instance,$(word 1,$(TUPLES))):8080

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

.DEFAULT_GOAL := help

.PHONY: help
help: ## список целей
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## собрать образы сервиса, нагрузчика и etcdctl
	$(COMPOSE) build

.PHONY: instances
instances: ## расклад инстансов: имя, порт, путь конфигурации
	@$(foreach t,$(TUPLES),printf "%-28s %-6s %s\n" "$(call tuple_instance,$(t))" "$(call tuple_port,$(t))" "$(call tuple_path,$(t))";)

.PHONY: up
up: ## поднять весь стенд и дождаться готовности всех инстансов и балансировщиков
	$(COMPOSE) up -d --build
	@echo "--- ждём готовности всех инстансов и балансировщиков (до $(WAIT_S)с) ---"
	@./scripts/wait-ready.sh "$(TUPLES)" $(LB_A_PORT) $(LB_B_PORT) $(WAIT_S)
	@$(MAKE) --no-print-directory pool

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

.PHONY: seed
seed: ## разложить стартовый конфиг по всем инстансам (идемпотентно)
	$(COMPOSE) run --rm --no-deps -e SEED_MAX_POOL_SIZE=$(SIZE) etcd-seed

# --- ручные правки конфигурации в etcd ---
# Адресация: I=имя инстанса (один), S=сервис (все инстансы сервиса), G=группа (все инстансы группы).

.PHONY: set-size
set-size: ## размер пула инстанса: make set-size SIZE=25 [I=имя]
	@$(if $(call tuple_by_I,$(I)),,@echo "не найден инстанс I=$(I)"; exit 1)
	@echo "etcd: $(call tuple_path,$(call tuple_by_I,$(I)))maximumPoolSize = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(call tuple_by_I,$(I)))maximumPoolSize" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory pool I=$(I)

.PHONY: set-service-size
set-service-size: ## размер пула всех инстансов сервиса: make set-service-size SIZE=25 S=service-a
	@$(if $(S),,@echo "укажи S=имя сервиса"; exit 1)
	@$(foreach t,$(call tuples_by_S),$(COMPOSE) run --rm --no-deps etcdctl put "$(call tuple_path,$(t))maximumPoolSize" "$(SIZE)";)
	@$(MAKE) --no-print-directory pool

.PHONY: set-group-size
set-group-size: ## размер пула всех инстансов группы: make set-group-size SIZE=25 G=group-1
	@$(if $(G),,@echo "укажи G=имя группы"; exit 1)
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

.PHONY: service-restart
service-restart: ## перезапустить инстанс: make service-restart P=service-a-group-1-1
	$(COMPOSE) restart $(P)

.PHONY: test
test: ## юнит-тесты сервиса
	cd service && mvn -B -q test