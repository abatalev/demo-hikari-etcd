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
# Шаблон по умолчанию — инстансы этого сервиса, а не 'service-%': сумма берётся по
# пулам одного сервиса, как и сравнивается с его бюджетом.
#
# Использование: measure-fleet.sh <service> [seconds] [шаблон имени] [период, с]
set -eu
SERVICE="${1:-}"
if [ -z "$SERVICE" ]; then
    echo "укажи сервис: measure-fleet.sh service-a [seconds] [шаблон] [период, с]" >&2
    exit 1
fi
SECS="${2:-20}"
PATTERN="${3:-${SERVICE}-group-%}"
PERIOD="${4:-0.1}"

# Потолок печатаем вместе с шапкой: смотреть на превышение без потолка бессмысленно.
# Недоступность справки о потолке замеру не мешает, поэтому ошибка не фатальна.
CEILING="$(psql -U "${POSTGRES_USER:-app}" -d "${POSTGRES_DB:-demo}" -At \
    -c "SELECT current_setting('max_connections')" 2>/dev/null || echo '?')"
printf 'база сервиса %s, потолок соединений %s, шаблон %s, период %sс, длительность %sс\n' \
    "$SERVICE" "$CEILING" "$PATTERN" "$PERIOD" "$SECS"

# Замеры идут по generate_series, пауза между ними — pg_sleep. Первый ряд печатается
# без паузы (справедливость: до него сон не нужен), остальные — после предыдущей.
psql -U "${POSTGRES_USER:-app}" -d "${POSTGRES_DB:-demo}" -At -c "
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
       WHERE application_name LIKE '${PATTERN}'
  ) AS ps
 ORDER BY ticks.i"