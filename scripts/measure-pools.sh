#!/bin/sh
# Поинстансная выборка сессий пула одной строкой на замер: t i1=… i2=… всего=N.
# Сумма по флоту скачет, и по одной сумке не понять, кто освобождал, а кто набирал.
# Использование: measure-pools.sh <seconds> [шаблон имени]
set -eu
SECS="${1:-20}"
PATTERN="${2:-service-%}"
i=0
while [ "$i" -lt $((SECS * 4)) ]; do
    psql -U "${POSTGRES_USER:-app}" -d "${POSTGRES_DB:-demo}" -At -F' ' -c \
        "SELECT to_char(now(),'SS.MS') || ' ' || p
         FROM (SELECT string_agg(application_name || '=' || sessions, ' ' ORDER BY application_name)
                          || ' всего=' || sum(sessions) AS p
               FROM pool_sessions WHERE application_name LIKE '${PATTERN}') s"
    i=$((i + 1))
    sleep 0.25
done
