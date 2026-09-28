#!/usr/bin/env bash
# Кладёт стартовый конфиг пула в etcd для каждого инстанса из ETCD_INSTANCES, если его там
# ещё нет (идемпотентно). Запускается автоматически сервисом etcd-seed при docker compose up.
set -euo pipefail

ENDPOINTS="${ETCDCTL_ENDPOINTS:-http://etcd:2379}"
ROOT="${ETCD_ROOT:-/config}"
MAX_POOL_SIZE="${SEED_MAX_POOL_SIZE:-10}"
CONNECTION_TIMEOUT_MS="${SEED_CONNECTION_TIMEOUT_MS:-3000}"

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

count=0
for t in $ETCD_INSTANCES; do
    svc="${t%%|*}" rest="${t#*|}"
    grp="${rest%%|*}" rest="${rest#*|}"
    inst="${rest%%|*}"
    PREFIX="$ROOT/$svc/$grp/$inst/hikari/"
    echo "etcd-seed: $PREFIX"
    put_if_absent "${PREFIX}maximumPoolSize" "$MAX_POOL_SIZE"
    # minimumIdle намеренно не сеем: null = "держать minimumIdle == maximumPoolSize".
    # Зафиксировать можно, положив сюда число, например: put_if_absent "${PREFIX}minimumIdle" 5
    put_if_absent "${PREFIX}connectionTimeoutMs" "$CONNECTION_TIMEOUT_MS"
    count=$((count + 1))
done

echo "etcd-seed: посеяно инстансов: $count (root=$ROOT, pool.max=$MAX_POOL_SIZE)"
echo "etcd-seed: текущий конфиг"
etcdctl get --prefix "$ROOT/"