#!/usr/bin/env bash
# Ждёт, пока все инстансы (порты из кортежей s|g|i|port) не станут готовы
# (/actuator/health/readiness = UP) и обе точки входа балансировщика не начнут маршрутизировать.
# С таймаутом: по истечении перечисляет, что не готово, и завершается с кодом 1.
#
# Сборщик метрик, сборщик метрик базы и витрина в это условие НЕ входят: недоступность наблюдения
# не должна ронять подъём стенда и мешать обслуживанию трафика. Их готовность ждём отдельно и
# коротко, а по итогам печатаем состояние — решение о том, мешает ли это, остаётся за человеком.
set -u

INSTANCES="$1"
LB_A_PORT="$2"
LB_B_PORT="$3"
WAIT_S="${4:-120}"
PROM_PORT="${5:-9090}"
GRAFANA_PORT="${6:-3000}"
EXPORTER_PORT_A="${7:-9187}"
EXPORTER_PORT_B="${8:-9188}"

# Окно ожидания наблюдения: отдельное и короткое, чтобы стенд не ждал его впустую.
OBS_WAIT_S="${OBS_WAIT_S:-60}"

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
    # Готовность распределителя проверяется маршрутизацией, а не его собственным /ping.
    # Разница существенна: /ping отвечает 200 всегда, в том числе когда бэкендов нет вовсе или
    # адреса не разрешились, поэтому такой probe зелёный на неработающем распределителе.
    # Запрос идёт через сам распределитель и должен вернуть ответ ИНСТАНСА — /api/pool, а не /ping
    # и не корень. Именно /api/pool, а не /api/work: работа при закрытом гейте честно отдаёт 503,
    # и тогда распределитель с живым маршрутом к неготовому флоту выглядел бы негодным.
    # Признак инстанса — поле etcd в теле ответа: /api/pool работает и при снятой конфигурации,
    # когда пула нет вовсе, поэтому искать poolName здесь нельзя — его в теле может не быть.
    # Проверяется каждая точка входа отдельно: у них разные маршруты, и точка с неработающим
    # маршрутом осталась бы зелёной по /ping.
    for lb_port in "$LB_A_PORT" "$LB_B_PORT"; do
        if ! curl -fsS --max-time 5 "http://localhost:$lb_port/api/pool" 2>/dev/null | grep -q '"etcd"'; then
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
        echo "проверь etcd и провижинеров: docker compose ps etcd config-provisioner-1 config-provisioner-2; docker compose logs config-provisioner-1 config-provisioner-2" >&2
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
    if [ ${#obs_left[@]} -eq 0 ] || [ "$(date +%s)" -ge "$obs_deadline" ]; then
        if [ ${#obs_left[@]} -gt 0 ]; then
            echo "  НЕ ГОТОВО (стенд работает): ${obs_left[*]}" >&2
            echo "  состояние контейнеров: docker compose ps prometheus postgres-exporter-a postgres-exporter-b grafana" >&2
            echo "  логи: docker compose logs prometheus postgres-exporter-a postgres-exporter-b grafana" >&2
        fi
        break
    fi
    sleep 2
done

exit 0