#!/usr/bin/env bash
# Кладёт стартовый конфиг пула в etcd, если его там ещё нет (идемпотентно).
# Запускается автоматически сервисом etcd-seed при docker compose up.
set -euo pipefail

ENDPOINTS="${ETCDCTL_ENDPOINTS:-http://etcd:2379}"
PREFIX="${POOL_PREFIX:-/config/pool-service/hikari/}"
MAX_POOL_SIZE="${SEED_MAX_POOL_SIZE:-10}"

echo "etcd-seed: endpoints=$ENDPOINTS prefix=$PREFIX"

# etcdctl put не имеет --ignore-existing, поэтому проверяем сами
put_if_absent() {
    local key="$1" value="$2"
    if [ -n "$(etcdctl get --print-value-only "$key")" ]; then
        echo "  $key уже задан, не трогаем"
        return
    fi
    etcdctl put "$key" "$value" >/dev/null
    echo "  $key = $value"
}

echo "etcd-seed: накладываем дефолты"
put_if_absent "${PREFIX}maximumPoolSize" "$MAX_POOL_SIZE"
# minimumIdle намеренно не сеем: null = "держать minimumIdle == maximumPoolSize".
# Зафиксировать можно, положив сюда число, например: put_if_absent "${PREFIX}minimumIdle" 5
put_if_absent "${PREFIX}connectionTimeoutMs" "${SEED_CONNECTION_TIMEOUT_MS:-3000}"

echo "etcd-seed: текущий конфиг"
etcdctl get --prefix "$PREFIX"
