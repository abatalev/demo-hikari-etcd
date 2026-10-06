#!/usr/bin/env python3
"""Живой состав инстансов стенда из `docker compose ps`.

Печатает кортежи `service|group|instance` по одному на строку:
  сервис и группа — из имени compose-сервиса группы (`service-a-group-1` → service-a, group-1);
  инстанс — короткий ID контейнера (в контейнере это `HOSTNAME`, он же имя узла регистрации
  и `POOL_NAME` пула).

Список берётся из `docker compose ps`, а не из `.env`: число реплик группы задаётся
`--scale`, и единственный источник того, что реально развёрнуто, — сам compose. Список
включает и остановленные контейнеры — правило молчания обязано жить и для упавшего
инстанса (инстанс без ряда, а не «не существует»).

Модуль импортируется другими скриптами (silence.py, check-instances.py, pool-all.py),
а как команда вызывается из Makefile (make instances).
"""

import json
import os
import subprocess
import sys


def containers():
    """Список контейнеров проекта: {service, id, status} из `docker compose ps`.

    Полный ID брать не нужно: короткого (12 hex) достаточно — он и есть HOSTNAME в контейнере.
    Ошибка compose (проект не поднят, docker недоступен) — пустой список и сообщение в stderr:
    вызывающей стороне решать, что это значит (состав пуст = стенд не поднят).
    """
    rows = []
    try:
        proc = subprocess.run(
            ["docker", "compose", "ps", "--format", "{{.Service}}\t{{.ID}}\t{{.Status}}"],
            capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as e:
        print(f"docker compose ps не выполнился: {e}", file=sys.stderr)
        return rows
    if proc.returncode != 0:
        print(f"docker compose ps: {proc.stderr.strip() or proc.stdout.strip()}", file=sys.stderr)
        return rows
    for line in proc.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            rows.append({"service": parts[0], "id": parts[1], "status": parts[2]})
    return rows


def parse_group_service(service):
    """`service-a-group-1` → (service-a, group-1); None для не-групповых сервисов."""
    if not service.startswith("service-") or "-group-" not in service:
        return None
    head, _, tail = service.partition("-group-")
    if not head or not tail:
        return None
    # Хвост после разделителя — номер группы (имя compose-сервиса группы `service-a-group-1`,
    # а группа называется `group-1`): имя группы восстанавливается, а не берётся хвостом.
    return head, "group-" + tail


def fleet():
    """Кортежи (service, group, instance) живого состава, отсортированные по полному ключу."""
    result = []
    for c in containers():
        parsed = parse_group_service(c["service"])
        if parsed is None:
            continue
        service, group = parsed
        result.append((service, group, c["id"]))
    return sorted(result)


def main():
    for service, group, instance in fleet():
        print(f"{service}|{group}|{instance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())