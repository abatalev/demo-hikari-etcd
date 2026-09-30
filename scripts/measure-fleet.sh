#!/bin/sh
# Непрерывная выборка числа соединений флота: пик по сумме нужен, чтобы поймать
# превышение бюджета в переходе, а не только установившийся режим.
#
# Частота важна: превышение живёт доли секунды, и редкий опрос его пропускает.
# Здесь один psql и его \watch — на каждый замер не поднимается процесс.
#
# Использование: measure-fleet.sh <seconds> [шаблон имени] [период, с]
set -eu
SECS="${1:-20}"
PATTERN="${2:-service-%}"
PERIOD="${3:-0.1}"
i=0
while [ "$i" -lt "$SECS" ]; do
    printf '%s\n' \
        "SELECT to_char(clock_timestamp(),'SS.MS')
                || ' ' || coalesce(sum(sessions), 0) AS total
         FROM pool_sessions WHERE application_name LIKE '${PATTERN}'" \
        "\\watch ${PERIOD}" \
    | psql -U "${POSTGRES_USER:-app}" -d "${POSTGRES_DB:-demo}" -At
    i=$((i + 1))
    sleep "$PERIOD"
done
