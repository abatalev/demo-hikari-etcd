#!/bin/sh
# Непрерывная выборка числа соединений флота: пик по сумме нужен, чтобы поймать
# превышение бюджета в переходе, а не только установившийся режим.
#
# Частота важна: превышение живёт доли секунды, и редкий опрос его пропускает.
# Один запрос и `generate_series` с `pg_sleep` внутри: длительность задана числом
# замеров, а не внешним циклом. \watch для этого не годится — в PostgreSQL 16 у него
# нет числа повторов, прогон заканчивается только по Ctrl-C, то есть длительность
# неуправляема (проверено на стенде: `\watch 3 0.1` → «interval value is specified
# more than once», а `\watch 0.1` крутится вечно).
#
# База на сервис, поэтому сервис обязателен: шаблон имени без него на двух базах
# выглядел бы как один флот, а это ровно та путаница, которой адресация избегает.
# Default PATTERN='-' означает «пулы сервиса» — IN-список из узлов регистрации etcd
# (scripts/reg-nodes.py): application_name пула равен имени узла регистрации, имена
# приходят из окружения, и никакой шаблон имён их не перечисляет.
#
# Использование: measure-fleet.sh <service> [seconds] [шаблон имени или '-'=авто] [период, с]
set -eu
SERVICE="${1:-}"
if [ -z "$SERVICE" ]; then
    echo "укажи сервис: measure-fleet.sh service-a [seconds] [шаблон] [период]" >&2
    exit 1
fi
SECS="${2:-20}"
PATTERN="${3:--}"
PERIOD="${4:-0.1}"

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

# Порт базы на хосте: у сервиса свой (база на сервис), PGPORT в окружении — в приоритете.
PORT="${PGPORT:-}"
if [ -z "$PORT" ]; then
    case "$SERVICE" in
        service-b) PORT="${POSTGRES_PORT_B:-5433}" ;;
        *) PORT="${POSTGRES_PORT_A:-5432}" ;;
    esac
fi
PSQL="psql -p $PORT -U ${POSTGRES_USER:-app} -d ${POSTGRES_DB:-demo}"

# Потолок печатаем вместе с шапкой: смотреть на превышение без потолка бессмысленно.
# Недоступность справки о потолке замеру не мешает, поэтому ошибка не фатальна.
CEILING="$("$PSQL" -At \
    -c "SELECT current_setting('max_connections')" 2>/dev/null || echo '?')"
printf 'база сервиса %s, потолок соединений %s, отбор %s, период %sс, длительность %sс\n' \
    "$SERVICE" "$CEILING" "$DESC" "$PERIOD" "$SECS"

# Замеры идут по generate_series, пауза между ними — pg_sleep. Первый ряд печатается
# без паузы (справедливость: до него сон не нужен), остальные — после предыдущей.
"$PSQL" -At -c "
WITH RECURSIVE ticks AS (
    SELECT 0 AS i
    UNION ALL
    SELECT i + 1 FROM ticks WHERE i + 1 < greatest(1, floor(${SECS} / ${PERIOD})::int)
)
SELECT to_char(clock_timestamp(), 'SS.MS') || ' ' || coalesce(ps.sessions, 0)
  FROM ticks
  CROSS JOIN LATERAL (SELECT pg_sleep(${PERIOD}) WHERE ticks.i > 0) AS pause
  CROSS JOIN LATERAL (
      SELECT coalesce(sum(sessions), 0) AS sessions
        FROM pool_sessions
       WHERE ${WHERE}
  ) AS ps
 ORDER BY ticks.i"