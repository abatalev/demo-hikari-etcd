#!/usr/bin/env bash
# Ждёт, пока все инстансы (состав `docker compose ps`) не станут готовы и обе точки входа
# балансировщика не начнут маршрутизировать. С таймаутом: по истечении перечисляет, что не
# готово, и завершается с кодом 1.
#
# Готовность инстанса видна двумя способами, и ждём оба:
#  - docker-здоровье (`docker compose ps`): контейнер здоров, когда его healthcheck прошёл, а у
#    инстанса это /actuator/health/readiness внутри контейнера — то есть гейт конфигурации;
#  - метрики: ряд pool_traffic_gate_open{node=...} равен 1, а причина закрытого гейта — в
#    pool_not_ready_reason. Метрики приходят отправкой в дверь, поэтому «здоров в контейнере»
#    и «виден в наблюдении» могут расходиться на десятки секунд — ждём оба.
#
# Хост-портов у инстансов нет: обращение к инстансу по HTTP из скрипта невозможно и не нужно.
# Состав берётся из `docker compose ps` (scripts/fleet.py): число реплик группы задано
# `--scale`, и список того, что развёрнуто, — единственный источник.
#
# Дверь сигналов, хранилища трасс и журналов, сборщик метрик, сборщик метрик базы и витрина в
# это условие НЕ входят: недоступность наблюдения не должна ронять подъём стенда и мешать
# обслуживанию трафика. Их готовность ждём отдельно и коротко, а по итогам печатаем состояние —
# решение о том, мешает ли это, остаётся за человеком.
set -u

LB_A_PORT="${1:-8080}"
LB_B_PORT="${2:-8081}"
WAIT_S="${3:-120}"
PROM_PORT="${4:-9090}"
GRAFANA_PORT="${5:-3000}"
EXPORTER_PORT_A="${6:-9187}"
EXPORTER_PORT_B="${7:-9188}"
OTEL_METRICS_PORT="${8:-9464}"
TEMPO_PORT="${9:-3200}"
LOKI_PORT="${10:-3100}"

# Окно ожидания наблюдения: отдельное и короткое, чтобы стенд не ждал его впустую.
OBS_WAIT_S="${OBS_WAIT_S:-60}"

HEREDIR="$(cd "$(dirname "$0")" && pwd)"

gate_open() {
    # ряд гейта трафика инстанса равен 1; пустой ответ или ряд со значением 0 — гейт закрыт
    curl -fsS --max-time 5 \
        "http://localhost:${PROM_PORT}/api/v1/query?query=pool_traffic_gate_open%7Bnode%3D%22$1%22%7D" \
        2>/dev/null | grep -q ',"1"]'
}

not_ready_reason() {
    # причина закрытого гейта: ряд pool_not_ready_reason со значением 1
    curl -fsS --max-time 5 \
        "http://localhost:${PROM_PORT}/api/v1/query?query=pool_not_ready_reason%7Bnode%3D%22$1%22%7D" \
        2>/dev/null | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for r in d.get("data", {}).get("result", []):
    if r.get("value", [None, "0"])[1] == "1":
        print(r.get("metric", {}).get("reason", ""))
        break'
}

deadline=$(( $(date +%s) + WAIT_S ))

while :; do
    failing=()
    ok=1

    # --- состав и docker-здоровье инстансов ---
    fleet="$("$HEREDIR/fleet.py")"
    if [ -z "$fleet" ]; then
        ok=0
        failing+=("состав пуст: docker compose ps не показывает ни одного инстанса (стенд не поднят)")
    else
        ids="$(printf '%s\n' "$fleet" | cut -d'|' -f3 | paste -sd' ' -)"
        declare -A health_of
        while IFS=$'\t' read -r id health; do
            health_of["$id"]="$health"
        done < <(docker compose ps --format '{{.ID}}\t{{.Health}}' 2>/dev/null)
        for id in $ids; do
            health="${health_of[$id]:-}"
            if [ -z "$health" ]; then
                ok=0
                failing+=("$id (нет в docker compose ps)")
            elif [ "$health" != "healthy" ]; then
                ok=0
                failing+=("$id (docker: $health)")
            fi
        done
        # метрики: у каждого инстанса гейт трафика открыт (причина закрытия — для отчёта)
        for id in $ids; do
            if ! gate_open "$id"; then
                ok=0
                failing+=("$id (гейт закрыт: $(not_ready_reason "$id"))")
            fi
        done
    fi

    # Готовность распределителя проверяется маршрутизацией, а не его собственным /ping.
    # Разница существенна: /ping отвечает 200 всегда, в том числе когда бэкендов нет вовсе или
    # адреса не разрешились, поэтому такой probe зелёный на неработающем распределителе.
    # Запрос идёт через сам распределитель и должен вернуть ответ ИНСТАНСА, а не /ping и не корень.
    # Точка — /actuator/prometheus, а не нагрузочная /api/work и не /actuator/health/readiness:
    # обе честно отдают 503 при закрытом гейте, и тогда распределитель с живым маршрутом к
    # неготовому флоту выглядел бы негодным. Метрики же отдаются всегда — и при закрытом гейте,
    # и при снятой конфигурации, когда пула нет вовсе.
    # Признак инстанса — ряд pool_config_state: он есть у процесса всегда, независимо от наличия
    # конфигурации. Проверяется каждая точка входа отдельно: у них разные маршруты, и точка с
    # неработающим маршрутом осталась бы зелёной по /ping.
    for lb_port in "$LB_A_PORT" "$LB_B_PORT"; do
        if ! curl -fsS --max-time 5 "http://localhost:$lb_port/actuator/prometheus" 2>/dev/null | grep -q 'pool_config_state{'; then
            ok=0
            failing+=("lb@$lb_port")
        fi
    done
    if [ "$ok" -eq 1 ]; then
        echo "все инстансы и точки входа распределителя готовы"
        break
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "ТАЙМАУТ (${WAIT_S}с): не готовы: ${failing[*]}" >&2
        echo "проверь etcd и провижинеров: docker compose ps etcd config-provisioner; docker compose logs config-provisioner" >&2
        echo "расклад по инстансам: make instances; сводка: make pool" >&2
        exit 1
    fi
    sleep 2
done

# Наблюдение ждём отдельно и его недоступность не считаем ошибкой стенда.
echo "--- наблюдение (не условие готовности, до ${OBS_WAIT_S}с) ---"
obs_deadline=$(( $(date +%s) + OBS_WAIT_S ))
while :; do
    obs_left=()
    check_obs() {
        if curl -fsS --max-time 5 "$1" >/dev/null 2>&1; then
            echo "  готов: $2"
        else
            obs_left+=("$2")
        fi
    }
    check_obs "http://localhost:${PROM_PORT}/-/ready" "сборщик метрик@${PROM_PORT}"
    # По сборщику на базу: сессии пулов видны в сборщике своей базы, и молчащий сборщик
    # второй базы означал бы, что на панели половина флота просто отсутствует.
    check_obs "http://localhost:${EXPORTER_PORT_A}/metrics" "сборщик метрик базы a@${EXPORTER_PORT_A}"
    check_obs "http://localhost:${EXPORTER_PORT_B}/metrics" "сборщик метрик базы b@${EXPORTER_PORT_B}"
    check_obs "http://localhost:${GRAFANA_PORT}/api/health" "витрина метрик@${GRAFANA_PORT}"
    # Дверь сигналов и её хранилища. Точка метрик двери вместо /ready: у образа без оболочки нет
    # ни wget, ни curl (в compose healthcheck не задаётся), а 9464/metrics отвечает только когда
    # дверь поднялась и держит накопленные величины. Хранилища отвечают своим /ready.
    check_obs "http://localhost:${OTEL_METRICS_PORT}/metrics" "дверь сигналов@${OTEL_METRICS_PORT}"
    check_obs "http://localhost:${TEMPO_PORT}/ready" "хранилище трасс@${TEMPO_PORT}"
    check_obs "http://localhost:${LOKI_PORT}/ready" "хранилище журналов@${LOKI_PORT}"
    if [ ${#obs_left[@]} -eq 0 ] || [ "$(date +%s)" -ge "$obs_deadline" ]; then
        if [ ${#obs_left[@]} -gt 0 ]; then
            echo "  НЕ ГОТОВО (стенд работает): ${obs_left[*]}" >&2
            echo "  состояние контейнеров: docker compose ps prometheus postgres-exporter-a postgres-exporter-b grafana otel-collector tempo loki" >&2
            echo "  логи: docker compose logs prometheus postgres-exporter-a postgres-exporter-b grafana otel-collector tempo loki" >&2
        fi
        break
    fi
    sleep 2
done

exit 0