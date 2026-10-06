#!/bin/sh
# Поинстансная выборка сессий пула одной строкой на замер: t i1=… i2=… всего=N.
# Сумма по флоту скачет, и по одной сумке не понять, кто освобождал, а кто набирал.
#
# База на сервис, поэтому сервис обязателен: поинстансная картина нескольких баз
# вперемешку не отвечает на вопрос «кто в своём флоте освобождал».
# Отбор — IN-список из узлов регистрации etcd (scripts/reg-nodes.py), а не шаблон имён:
# application_name пула равен имени узла регистрации, имена приходят из окружения.
# PATTERN='-' (по умолчанию) — узлы регистрации; явный шаблон — для ad-hoc фильтра.
#
# Использование: measure-pools.sh <service> [seconds] [шаблон имени или '-'=авто]
set -eu
SERVICE="${1:-}"
if [ -z "$SERVICE" ]; then
    echo "укажи сервис: measure-pools.sh service-a [seconds] [шаблон]" >&2
    exit 1
fi
SECS="${2:-20}"
PATTERN="${3:--}"

ROOT="${ETCD_ROOT:-/config}"

if [ "$PATTERN" = "-" ]; then
    NODES="$(docker compose run --rm --no-deps -T etcdctl get --prefix "${ROOT}/services/" -w json \
        | python3 "$(dirname "$0")/reg-nodes.py" "${ROOT}/" "$SERVICE")"
    if [ -z "$NODES" ]; then
        echo "нет зарегистрированных узлов сервиса $SERVICE (etcd недоступен или стенд не поднят)" >&2
        exit 1
    fi
    NODE_LIST="$(printf '%s\n' "$NODES" | sed "s/^/'/; s/$/'/" | paste -sd, -)"
    WHERE="application_name = ANY (ARRAY[${NODE_LIST}])"
    DESC="узлы регистрации (${NODE_LIST})"
else
    WHERE="application_name LIKE '${PATTERN}'"
    DESC="шаблон ${PATTERN}"
fi

PORT="${PGPORT:-}"
if [ -z "$PORT" ]; then
    case "$SERVICE" in
        service-b) PORT="${POSTGRES_PORT_B:-5433}" ;;
        *) PORT="${POSTGRES_PORT_A:-5432}" ;;
    esac
fi
PSQL="psql -p $PORT -U ${POSTGRES_USER:-app} -d ${POSTGRES_DB:-demo}"

echo "база сервиса $SERVICE, отбор $DESC"
i=0
while [ "$i" -lt $((SECS * 4)) ]; do
    "$PSQL" -At -F' ' -c \
        "SELECT to_char(now(),'SS.MS') || ' ' || p
         FROM (SELECT string_agg(application_name || '=' || sessions, ' ' ORDER BY application_name)
                          || ' всего=' || sum(sessions) AS p
               FROM pool_sessions WHERE ${WHERE}) s"
    i=$((i + 1))
    sleep 0.25
done