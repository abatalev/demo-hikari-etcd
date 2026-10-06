#!/usr/bin/env python3
"""Живые сервисы стенда из узлов регистрации etcd (вывод `etcdctl get -w json`).

Аргумент: корень в etcd, например `/config`.

Печатает по одному имени сервиса на строку: имена, у которых в дереве
`{root}/services/{service}/` есть живые узлы регистрации `.../instances/{instance}`.
Список сервисов выводится из регистрации, а не из перечня в конфигурации: инстансы
регистрируются по окружению развёртывания, и списка сервисов в репозитории нет.

Скрипт только читает и считает: на его выводе держится проверка `S=` в командах стенда
(make require-S), поэтому непрочитанный снимок — отказ с кодом 2, а не тихий пустой список.
"""

import base64
import json
import sys


def main():
    if len(sys.argv) < 2:
        print("usage: services.py <root-prefix>", file=sys.stderr)
        return 1
    root = sys.argv[1].rstrip("/") + "/services/"
    try:
        kvs = json.load(sys.stdin)["kvs"]
    except (json.JSONDecodeError, OSError) as e:
        print(f"снимок etcd не прочитан ({e}): etcd недоступен или стенд не поднят", file=sys.stderr)
        return 2

    services = set()
    for entry in kvs:
        key = base64.b64decode(entry["key"]).decode("utf-8")
        if not key.startswith(root):
            continue
        # {root}/services/{service}/groups/{group}/instances/{instance}[/…]
        parts = key[len(root):].split("/")
        if len(parts) >= 5 and parts[1] == "groups" and parts[3] == "instances":
            services.add(parts[0])
    for service in sorted(services):
        print(service)
    return 0


if __name__ == "__main__":
    sys.exit(main())