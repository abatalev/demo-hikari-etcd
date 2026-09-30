#!/usr/bin/env python3
"""Потолки и публикации инстансов по данным etcd (вывод `etcdctl get -w json`).

Аргумент: корень в etcd, например `/config/` (полный снимок, скрипт сам отфильтрует).

Печатает по каждому инстансу приказанный потолок `maximumPoolSize` и опубликованное
неосвобождённое сжатие `unreleasedConnections`, затем суммы: сколько флот занимает по отчётам
инстансов и сколько — по худшему случаю, который берёт провижёр (нет публикации или она от
прежнего потолка ⇒ инстанс мог держать весь потолок). Скрипт только читает и считает: превышение
бюджета фиксирует провижёр, а он; фактические соединения видно через `make fleet-sessions` в БД.
"""
import base64
import json
import sys

CEILING = "maximumPoolSize"
PUBLICATION = "unreleasedConnections"
SETTING_ACTIVE_MAX = "activeMaxConnections"


def decode(value):
    """etcdctl -w json отдаёт ключи и значения в base64."""
    return base64.b64decode(value).decode("utf-8")


def main():
    if len(sys.argv) < 2:
        print("usage: fleet-sessions.py <root-prefix>", file=sys.stderr)
        return 1
    root = sys.argv[1].rstrip("/")
    kvs = json.load(sys.stdin)["kvs"]

    # {root}/services/{service}/groups/{group}/instances/{instance}/{hikari/…|unreleasedConnections}
    ceilings = {}
    published = {}
    budgets = {}
    nodes = set()
    for entry in kvs:
        key = decode(entry["key"])
        if not key.startswith(root + "/services/"):
            continue
        rest = key[len(root) + len("/services/"):]
        parts = rest.split("/")
        if len(parts) == 2 and parts[1] == SETTING_ACTIVE_MAX:
            budgets[parts[0]] = decode(entry["value"])
            continue
        if len(parts) < 5 or parts[1] != "groups" or parts[3] != "instances":
            continue
        node = "/".join([parts[0], parts[2], parts[4]])
        nodes.add(node)
        if len(parts) == 6 and parts[5] == PUBLICATION:
            published[node] = decode(entry["value"])
        elif len(parts) == 7 and parts[5] == "hikari" and parts[6] == CEILING:
            ceilings[node] = decode(entry["value"])

    if not nodes:
        print("  (живых инстансов в etcd нет)")
        return 0

    def number(value):
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    # По отчёту инстанса занято столько, сколько он сам написал. Если публикации нет или она
    # относится к прежнему потолку, провижёр берёт худший случай — весь потолок: инстанс мог
    # держать больше, чем показывает. Обе величины показываем, разница и есть цена неопределённости.
    rows = []
    for node in sorted(nodes):
        ceiling = number(ceilings.get(node)) or 0
        debt = number(published.get(node))
        reported = ceiling + max(0, debt) if debt is not None else ceiling
        worst = ceiling + max(0, debt) if debt is not None else 2 * ceiling
        rows.append((node, ceiling, debt, reported, worst))

    width = max(len(node) for node, _, _, _, _ in rows)
    print(f"  {'инстанс'.ljust(width)}  потолок  долг  по отчёту  худший случай")
    for node, ceiling, debt, reported, worst in rows:
        note = "" if ceiling else "  (без доли: не обслуживается)" if debt is None else \
            "  (конфигурации нет, дренаж)"
        print(f"  {node.ljust(width)}  {ceiling:>7}"
              f"  {('—' if debt is None else debt):>4}  {reported:>9}  {worst:>13}{note}")

    sum_ceilings = sum(c for _, c, _, _, _ in rows)
    sum_reported = sum(r for _, _, _, r, _ in rows)
    sum_worst = sum(w for _, _, _, _, w in rows)
    print(f"  сумма потолков: {sum_ceilings}   по отчёту: {sum_reported}   "
          f"худший случай (его видит провижёр): {sum_worst}")

    by_service = {}
    for node, _, _, reported, worst in rows:
        entry = by_service.setdefault(node.split("/")[0], [0, 0])
        entry[0] += reported
        entry[1] += worst
    for service, (reported, worst) in sorted(by_service.items()):
        budget = number(budgets.get(service))
        limit = budget if budget is not None else "—"
        verdict = "—" if budget is None else ("OK" if worst <= budget else
                                             f"ПРЕВЫШЕНИЕ на {worst - budget}")
        print(f"  {service}: по отчёту {reported}, худший случай {worst}, бюджет N={limit}"
              f" → {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
