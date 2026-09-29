#!/usr/bin/env bash
# Ждёт, пока все инстансы (порты из кортежей s|g|i|port) не станут готовы
# (/actuator/health/readiness = UP) и оба балансировщика не ответят на /healthz.
# С таймаутом: по истечении перечисляет, что не готово, и завершается с кодом 1.
set -u

INSTANCES="$1"
LB_A_PORT="$2"
LB_B_PORT="$3"
WAIT_S="${4:-120}"

deadline=$(( $(date +%s) + WAIT_S ))

while :; do
    failing=()
    ok=1
    for t in $INSTANCES; do
        port="${t##*|}"
        if ! curl -fsS --max-time 5 "http://localhost:$port/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; then
            ok=0
            failing+=("инстанс@$port")
        fi
    done
    for lb_port in "$LB_A_PORT" "$LB_B_PORT"; do
        if ! curl -fsS --max-time 5 "http://localhost:$lb_port/healthz" >/dev/null 2>&1; then
            ok=0
            failing+=("lb@$lb_port")
        fi
    done
    if [ "$ok" -eq 1 ]; then
        echo "все инстансы и балансировщики готовы"
        exit 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "ТАЙМАУТ (${WAIT_S}с): не готовы: ${failing[*]}" >&2
        echo "проверь etcd и провижинер: docker compose ps etcd config-provisioner; docker compose logs config-provisioner" >&2
        echo "расклад по инстансам: make instances; сводка: make pool" >&2
        exit 1
    fi
    sleep 2
done