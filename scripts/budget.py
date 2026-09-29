#!/usr/bin/env python3
"""Сводка сервисного бюджета соединений по данным etcd (вывод `etcdctl get -w json`).

Аргументы:
  1) кортежи ETCD_INSTANCES этого сервиса через пробел: `service|group|instance|port`
  2) корень в etcd, например `/config/` (полный снимок: скрипт сам отфильтрует поддерево сервиса)

Печатает бюджет N (activeMaxConnections), минимум m (activeMinConnections), резерв неактивного
флота R (inactiveMaxConnections), маркеры групп из ``{root}/groups/``, живые инстансы с долями
`maximumPoolSize` и их сумму. Скрипт только читает и считает: проверку «сумма долей = N»
выполняет провижёр, а не он.
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
        print("usage: budget.py '<tuples>' <root-prefix>", file=sys.stderr)
        return 1
    tuples = sys.argv[1].split()
    root = sys.argv[2].rstrip("/")
    kvs = json.load(sys.stdin)["kvs"]

    if not tuples:
        print("  (нет инстансов в ETCD_INSTANCES)")
        return 0
    service, group0, instance0, _ = tuples[0].split("|")

    svc = root + "/services/" + service
    budget = kv(kvs, svc + "/activeMaxConnections")
    minimum = kv(kvs, svc + "/activeMinConnections")
    reserve = kv(kvs, svc + "/inactiveMaxConnections")
    print(f"  бюджет N: {budget if budget is not None else '—'}"
          f"   минимум m: {minimum if minimum is not None else '—'}"
          f"   резерв R: {reserve if reserve is not None else '—'}")

    # Маркеры активности групп (глобальный флот): {root}/groups/{group}/active
    groups = sorted({t.split("|")[1] for t in tuples})
    markers = {}
    for entry in kvs:
        key = decode(entry["key"])
        head = root + "/groups/"
        if key.startswith(head) and key.endswith("/active"):
            markers[key[len(head):-len("/active")]] = decode(entry["value"])
    flags = "  ".join(f"{g}: {markers.get(g, 'активна (маркера нет)')}" for g in groups)
    print(f"  группы ({len(groups)}): {flags}")

    live = set()
    shares = {}
    for entry in kvs:
        key = decode(entry["key"])
        if key.startswith(svc + "/groups/") and "/instances/" in key and "/hikari/" not in key:
            live.add(key)
        if key.startswith(svc + "/groups/") and key.endswith("/hikari/maximumPoolSize"):
            shares[key[: -len("maximumPoolSize")]] = decode(entry["value"])

    print(f"  живых инстансов: {len(live)}   с долей: {len(shares)}")
    total = 0
    for t in sorted(tuples):
        instance = t.split("|")[2]
        group = t.split("|")[1]
        path = next((p for p in shares if f"/instances/{instance}/" in p), None)
        size = shares.get(path) if path else None
        inactive = markers.get(group, "true").strip().lower() == "false"
        tag = " (НЕАКТИВНАЯ группа)" if inactive else ""
        if size is None:
            print(f"  {instance:<26} — без доли{tag} (не обслуживает трафик)")
            continue
        total += int(size)
        print(f"  {instance:<26} {size:>4}{tag}")
    print(f"  сумма долей: {total}")
    without_share = len(live) - len(shares)
    if budget is not None and budget.isdigit():
        inactive = sum(1 for t in tuples
                       if markers.get(t.split("|")[1], "true").strip().lower() == "false")
        delta = total - int(budget)
        if delta > 0:
            print(f"  ПРЕВЫШЕНИЕ БЮДЖЕТА на {delta}")
        elif total < int(budget):
            if without_share > 0 or inactive > 0:
                if inactive > 0 and without_share == 0:
                    print(f"  недобор {int(budget) - total}: резерв R×k не тянет весь бюджет (сумма по "
                          f"сервису ≤ N, активные получили N − R×k)")
                else:
                    print(f"  недобор {int(budget) - total}: {without_share} инстанс(ов) без доли — "
                          f"бюджет не тянет состав на минимум")
            else:
                print(f"  недобор {int(budget) - total}: доли усечены до PROV_MAX_SHARE")
    elif budget is not None:
        print(f"  бюджет нечисловой: {budget!r} — провижёр не пересчитывает доли (см. его логи)")
    return 0


if __name__ == "__main__":
    sys.exit(main())