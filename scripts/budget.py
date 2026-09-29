#!/usr/bin/env python3
"""Сводка сервисного бюджета соединений по данным etcd (вывод `etcdctl get -w json`).

Аргументы:
  1) кортежи ETCD_INSTANCES этого сервиса через пробел: `service|group|instance|port`
  2) префикс сервиса в etcd, например `/config/services/service-a/`

Печатает бюджет N, минимум m, число живых инстансов, доли `maximumPoolSize` и их сумму.
Скрипт только читает и считает: проверку «сумма долей = N» выполняет провижёр, а не он.
"""
import base64
import json
import sys


def decode(value):
    """etcdctl -w json отдаёт ключи и значения в base64."""
    return base64.b64decode(value).decode("utf-8")


def kv(kvs, key):
    """Значение ключа из снимка etcd или None."""
    for entry in kvs:
        if decode(entry["key"]) == key:
            return decode(entry["value"])
    return None


def main():
    if len(sys.argv) < 3:
        print("usage: budget.py '<tuples>' <service-prefix>", file=sys.stderr)
        return 1
    tuples = sys.argv[1].split()
    prefix = sys.argv[2]
    kvs = json.load(sys.stdin)["kvs"]

    budget = kv(kvs, prefix + "maxConnections")
    minimum = kv(kvs, prefix + "minConnections")
    print(f"  бюджет N: {budget if budget is not None else '—'}"
          f"   минимум m: {minimum if minimum is not None else '—'}")

    live = set()
    shares = {}
    for entry in kvs:
        key = decode(entry["key"])
        if "/instances/" in key and "/hikari/" not in key:
            live.add(key)
        if key.endswith("/hikari/maximumPoolSize"):
            shares[key[: -len("maximumPoolSize")]] = decode(entry["value"])

    print(f"  живых инстансов: {len(live)}   с долей: {len(shares)}")
    total = 0
    for t in sorted(tuples):
        instance = t.split("|")[2]
        path = next((p for p in shares if f"/instances/{instance}/" in p), None)
        size = shares.get(path) if path else None
        if size is None:
            print(f"  {instance:<26} — без доли (не обслуживает трафик)")
            continue
        total += int(size)
        print(f"  {instance:<26} {size:>4}")
    print(f"  сумма долей: {total}")
    without_share = len(live) - len(shares)
    if budget is not None and budget.isdigit():
        delta = total - int(budget)
        if delta > 0:
            print(f"  ПРЕВЫШЕНИЕ БЮДЖЕТА на {delta}")
        elif total < int(budget):
            if without_share > 0:
                print(f"  недобор {int(budget) - total}: {without_share} инстанс(ов) без доли — "
                      f"бюджет не тянет состав на минимум")
            else:
                print(f"  недобор {int(budget) - total}: доли усечены до PROV_MAX_SHARE")
    elif budget is not None:
        print(f"  бюджет нечисловой: {budget!r} — провижёр не пересчитывает доли (см. его логи)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
