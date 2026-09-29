#!/usr/bin/env python3
"""Кто ведёт каждый сервис: лидер выборов провизора по ключам {root}/provisioner/leader/.

Читает со stdin вывод `etcdctl get --prefix {root}/provisioner/leader/ -w json`.
Лидер — ключ с наименьшим create_revision в префиксе сервиса (старейший из живых
кампаний); значение ключа — имя реплики (PROV_NAME).
"""
import base64
import json
import sys


def parse_leader_key(key):
    """(service, node_key) из полного ключа; None, если ключ не похож на ключ выборов.

    Формат ключа кампании: {root}/provisioner/leader/{service}/{hex аренды реплики} (провижер
    пишет его сам атомарным txn «ключа нет → put» с привязкой к аренде).
    """
    decoded = base64.b64decode(key).decode("utf-8")
    marker = "/provisioner/leader/"
    idx = decoded.rfind(marker)
    if idx < 0:
        return None
    rest = decoded[idx + len(marker):]
    if "/" not in rest:
        return None
    service, _sep, node = rest.rpartition("/")
    return service.rstrip("/"), node


def main():
    data = json.load(sys.stdin)
    kvs = data.get("kvs", [])
    if not kvs:
        print("лидеров нет: префикс {root}/provisioner/leader/ пуст")
        return
    by_service = {}
    for kv in kvs:
        parsed = parse_leader_key(kv["key"])
        if parsed is None:
            continue
        service, node = parsed
        create_rev = int(kv["create_revision"])
        value = base64.b64decode(kv["value"]).decode("utf-8")
        cur = by_service.get(service)
        if cur is None or create_rev < cur[0]:
            by_service[service] = (create_rev, value, node)
    if not by_service:
        print("ключей выборов не найдено")
        return
    for service in sorted(by_service):
        _create_rev, replica, node = by_service[service]
        print(f"{service}: {replica} (ключ .../{node})")


if __name__ == "__main__":
    main()