#!/usr/bin/env python3
"""Имена инстансов сервиса из узлов регистрации etcd (вывод `etcdctl get -w json`).

Аргументы: корень в etcd (например `/config`) и имя сервиса.

Печатает по одному имени инстанса на строку. Имена берутся из узлов регистрации, а не из
шаблона имён: `application_name` пула равен имени узла регистрации (POOL_NAME = instance),
поэтому узлы регистрации — единственный источник, перечисляющий пулы сервиса, и он же
обязан давать IN-список сессий флота (make fleet-sessions) при любом соглашении об именах.
"""

import base64
import json
import sys


def main():
    if len(sys.argv) < 3:
        print("usage: reg-nodes.py <root-prefix> <service>", file=sys.stderr)
        return 1
    root = sys.argv[1].rstrip("/") + "/services/" + sys.argv[2] + "/groups/"
    try:
        kvs = json.load(sys.stdin)["kvs"]
    except (json.JSONDecodeError, OSError) as e:
        print(f"снимок etcd не прочитан ({e}): etcd недоступен или стенд не поднят", file=sys.stderr)
        return 2

    nodes = set()
    for entry in kvs:
        key = base64.b64decode(entry["key"]).decode("utf-8")
        if not key.startswith(root):
            continue
        # {group}/instances/{instance}[/…]
        parts = key[len(root):].split("/")
        if len(parts) >= 3 and parts[1] == "instances":
            nodes.add(parts[2])
    for node in sorted(nodes):
        print(node)
    return 0


if __name__ == "__main__":
    sys.exit(main())