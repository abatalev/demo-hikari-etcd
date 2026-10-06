#!/usr/bin/env python3
"""Сводка сервисного бюджета соединений по данным etcd (вывод `etcdctl get -w json`).

Аргументы:
  1) корень в etcd, например `/config` (полный снимок: скрипт сам отфильтрует поддеревья)
  2) необязательное имя сервиса — тогда показывается только он; без него — все живые

Печатает по каждому сервису: бюджет N (activeMaxConnections), минимум m (activeMinConnections),
резерв неактивного флота R (inactiveMaxConnections), маркеры групп того же сервиса из
``{root}/groups/``, живые инстансы с долями `maximumPoolSize` и их сумму. Скрипт только читает
и считает: проверку «сумма долей = N» выполняет провижёр, а не он. Провал чтения снимка —
отказ (код 2), а не тихий пустой список: сводка на нечитаемом etcd читалась бы как
«борьбы нет».

Состав берётся из узлов регистрации, а не из перечня: инстансы регистрируются по окружению
(`docker compose ps` → HOSTNAME), и списка сервисов в конфигурации нет. Группы тоже выводятся
из живых узлов: у группы, сжатой в ноль реплик, узлов нет, и из сводки она исчезает вместе
с инстансами (маркер активности при этом остаётся в etcd — смотрится `make set-group-active`,
то есть ключ ``{root}/groups/{group}/active``).
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
    if len(sys.argv) < 2:
        print("usage: budget.py <root-prefix> [service]", file=sys.stderr)
        return 1
    root = sys.argv[1].rstrip("/")
    only = sys.argv[2] if len(sys.argv) > 2 else ""
    try:
        kvs = json.load(sys.stdin)["kvs"]
    except (json.JSONDecodeError, OSError) as e:
        print(f"снимок etcd не прочитан ({e}): etcd недоступен или стенд не поднят", file=sys.stderr)
        return 2

    services_prefix = root + "/services/"
    # {сервис: {группа: {инстанс}}} — из узлов регистрации .../instances/{instance}[/…]
    tree = {}
    for entry in kvs:
        key = decode(entry["key"])
        if not key.startswith(services_prefix):
            continue
        parts = key[len(services_prefix):].split("/")
        if len(parts) >= 5 and parts[1] == "groups" and parts[3] == "instances":
            tree.setdefault(parts[0], {}).setdefault(parts[2], set()).add(parts[4])

    if only:
        if only not in tree:
            live = ", ".join(sorted(tree)) or "нет"
            print(f"неизвестный сервис {only} (живые по регистрации: {live})", file=sys.stderr)
            return 1
        services = [only]
    else:
        services = sorted(tree)
        if not services:
            print("  (нет зарегистрированных инстансов — стенд не поднят?)")

    # Маркеры активности групп (глобальный флот): {root}/groups/{group}/active
    markers = {}
    for entry in kvs:
        key = decode(entry["key"])
        head = root + "/groups/"
        if key.startswith(head) and key.endswith("/active"):
            markers[key[len(head):-len("/active")]] = decode(entry["value"])

    for service in services:
        svc = services_prefix + service
        budget = kv(kvs, svc + "/activeMaxConnections")
        minimum = kv(kvs, svc + "/activeMinConnections")
        reserve = kv(kvs, svc + "/inactiveMaxConnections")
        print(f"== {service} ==")
        print(f"  бюджет N: {budget if budget is not None else '—'}"
              f"   минимум m: {minimum if minimum is not None else '—'}"
              f"   резерв R: {reserve if reserve is not None else '—'}")

        groups = sorted(tree[service])
        flags = "  ".join(f"{g}: {markers.get(g, 'активна (маркера нет)')}" for g in groups)
        print(f"  группы ({len(groups)}): {flags}")

        live = sorted((g, i) for g in tree[service] for i in tree[service][g])
        shares = {}
        for entry in kvs:
            key = decode(entry["key"])
            if key.startswith(svc + "/groups/") and key.endswith("/hikari/maximumPoolSize"):
                shares[key[: -len("maximumPoolSize")]] = decode(entry["value"])

        print(f"  живых инстансов: {len(live)}   с долей: {len(shares)}")
        total = 0
        for group, instance in live:
            path = f"{svc}/groups/{group}/instances/{instance}/hikari/"
            size = shares.get(path)
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
            inactive_groups = sum(1 for g in groups
                                  if markers.get(g, "true").strip().lower() == "false")
            delta = total - int(budget)
            if delta > 0:
                print(f"  ПРЕВЫШЕНИЕ БЮДЖЕТА на {delta}")
            elif total < int(budget):
                if without_share > 0 or inactive_groups > 0:
                    if inactive_groups > 0 and without_share == 0:
                        print(f"  недобор {int(budget) - total}: резерв R×k не тянет весь бюджет "
                              f"(сумма по сервису ≤ N, активные получили N − R×k)")
                    else:
                        print(f"  недобор {int(budget) - total}: {without_share} инстанс(ов) без "
                              f"доли — бюджет не тянет состав на минимум")
                else:
                    print(f"  недобор {int(budget) - total}: доли усечены до PROV_MAX_SHARE")
        elif budget is not None:
            print(f"  бюджет нечисловой: {budget!r} — провижёр не пересчитывает доли (см. его логи)")
    return 0


if __name__ == "__main__":
    sys.exit(main())