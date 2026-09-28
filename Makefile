SHELL := /bin/bash
COMPOSE ?= docker compose
PREFIX ?= /config/pool-service/hikari/
SIZE ?= 10
WORKERS ?= 4
WORK_MS ?= 25
REPORT_MS ?= 2000
DURATION_S ?= 0

.DEFAULT_GOAL := help

.PHONY: help
help: ## список целей
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## собрать образы сервиса, нагрузчика и etcdctl
	$(COMPOSE) build

.PHONY: up
up: ## поднять весь стенд (сервис + postgres + etcd + нагрузка)
	$(COMPOSE) up -d --build
	@echo "--- ждём, пока сервис возьмёт конфиг из etcd ---"
	@until curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; do sleep 1; done
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
logs: ## логи сервиса (S=1 — с etcd, F — follow)
	$(COMPOSE) logs -f --tail=200 $(S) service

.PHONY: load-logs
load-logs: ## логи нагрузчика
	$(COMPOSE) logs -f --tail=100 loadgen

.PHONY: stress
stress: ## разовый прогон нагрузки: WORKERS=32 WORK_MS=200 DURATION_S=20 make stress
	$(COMPOSE) run --rm --no-deps \
		-e WORKERS=$(WORKERS) -e WORK_MS=$(WORK_MS) -e REPORT_MS=$(REPORT_MS) \
		-e DURATION_S=$(or $(DURATION_S),20) loadgen

.PHONY: seed
seed: ## разложить стартовый конфиг в etcd (идемпотентно)
	$(COMPOSE) run --rm --no-deps -e SEED_MAX_POOL_SIZE=$(SIZE) etcd-seed

.PHONY: set-size
set-size: ## изменить размер пула на лету: make set-size SIZE=25
	@if ! [[ "$(SIZE)" =~ ^[0-9]+$$ ]]; then echo "SIZE должен быть числом"; exit 1; fi
	@echo "etcd: $(PREFIX)maximumPoolSize = $(SIZE)"
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(PREFIX)maximumPoolSize" "$(SIZE)"
	@sleep 1
	@$(MAKE) --no-print-directory pool

.PHONY: set-min-idle
set-min-idle: ## зафиксировать minimumIdle: make set-min-idle SIZE=3
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(PREFIX)minimumIdle" "$(SIZE)"

.PHONY: unset-min-idle
unset-min-idle: ## убрать minimumIdle (снова follow за maximumPoolSize)
	@$(COMPOSE) run --rm --no-deps etcdctl del "$(PREFIX)minimumIdle"

.PHONY: config
config: ## показать конфиг пула из etcd
	@$(COMPOSE) run --rm --no-deps etcdctl get --prefix "$(PREFIX)"

.PHONY: set-conn-timeout
set-conn-timeout: ## connectionTimeoutMs: make set-conn-timeout SIZE=1000
	@$(COMPOSE) run --rm --no-deps etcdctl put "$(PREFIX)connectionTimeoutMs" "$(SIZE)"

.PHONY: pool
pool: ## текущее состояние пула глазами сервиса
	@curl -fsS http://localhost:8080/api/pool | python3 -m json.tool

.PHONY: work
work: ## один запрос, как его делает нагрузчик
	@curl -fsS "http://localhost:8080/api/work?ms=100" | python3 -m json.tool

.PHONY: psql
psql: ## зайти в postgres
	@$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo}

.PHONY: sessions
sessions: ## сессии postgres по application_name (сколько коннектов держит пул)
	@$(COMPOSE) exec -T postgres psql -U $${POSTGRES_USER:-app} -d $${POSTGRES_DB:-demo} \
		-c "SELECT * FROM pool_sessions"

.PHONY: service-restart
service-restart: ## перезапустить только сервис (etcd-конфиг перечитается при старте)
	$(COMPOSE) restart service

.PHONY: test
test: ## юнит-тесты сервиса
	cd service && mvn -B -q test
