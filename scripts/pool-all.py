#!/usr/bin/env python3
"""Сводка по всем инстансам стенда одной командой: готовность, пул, путь конфигурации.

Аргументы:
  1) корень в etcd, например /config (из него печатается путь конфигурации инстанса)
  2) адрес сборщика метрик (по умолчанию http://127.0.0.1:9090)

Перечень инстансов — объединение живого состава (`docker compose ps`, scripts/fleet.py) и узлов
регистрации в etcd (снимок со stdin): контейнер, который ещё не зарегистрировался, даёт первый
источник, узел, чей контейнер уже остановлен (аренда ещё не истекла), — второй. Оба говорят об
инстансах, которых ни один перечень в конфигурации не перечисляет: число реплик задано `--scale`,
имена приходят из окружения.

На stdin — снимок etcd (`etcdctl get --prefix <root>/services/ -w json`). Он читается, а не
запрашивается по одному инстансу: один вызов etcdctl на весь вывод. Пустой или нечитаемый
stdin — не ошибка: снимок может быть недоступен (etcd лежит), и тогда колонка ключей печатается
как «н/д».

Три источника, у каждого своя роль: числа пула и готовность — из метрик (сборщик их не
опрашивает, они приходят в дверь; готовность — ряд гейта трафика, причина закрытия — ряд
`pool_not_ready_reason`), путь и наличие конфигурации — из etcd. Ни один из них не требует от
инстанса отдельной точки наблюдения: хост-портов у инстансов нет, и HTTP-запрос к инстансу из
сводки невозможен и не нужен.

Каждый источник переживает отказ независимо: сборщик недоступен — числа пула печатаются как «?»,
готовность как «н/д»; снимок etcd недоступен — колонка ключей «н/д». Ни один отказ не убирает
строку инстанса из вывода: пустой выводимый список при неготовом стенде читался бы как «всё
хорошо, печатать нечего».
"""
import base64
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

from fleet import fleet

# Числа пула, гейт трафика и причина его закрытия: один запрос на весь флот. Отбор идёт
# регуляркой по имени метрики, а НЕ через `or` из имён: при совпадении по набору признаков
# правая часть `or` отбрасывается как уже покрытая левой, и запрос вернул бы только первую
# метрику — тихо, без ошибки. Регулярка привязана целиком, поэтому `pool_connections_awaiting`
# и `pool_config_maximum_pool_size` под неё не попадают.
METRICS = ("pool_maximum_pool_size", "pool_minimum_idle",
           "pool_connections_open", "pool_connections_busy", "pool_connections_idle",
           "pool_traffic_gate_open", "pool_not_ready_reason")
QUERY = '{__name__=~"' + "|".join(METRICS) + '"}'

GATE = "pool_traffic_gate_open"
REASON = "pool_not_ready_reason"


def fetch_json(url, timeout=15):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def read_snapshot():
    """Ключи и значения etcd из снимка на stdin или (None, None), если снимка нет."""
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return None, None
    keys, values = [], {}
    for entry in data.get("kvs", []):
        key = base64.b64decode(entry["key"]).decode("utf-8")
        keys.append(key)
        # Узлы регистрации имеют пустое значение; etcd отдаёт JSON без поля "value" для пустых
        # значений — отсутствие поля означает пустую строку, а не сбой снимка.
        values[key] = base64.b64decode(entry.get("value", "")).decode("utf-8")
    return keys, values


def registered_instances(keys, root):
    """Инстансы из узлов регистрации: {(service, group, instance)}.

    Путь узла: {root}/services/{service}/groups/{group}/instances/{instance}[/…], то есть
    снимок со stdin разбирается по сегментам, как в services.py/reg-nodes.py: ни один
    перечень в конфигурации инстансы не называет.
    """
    prefix = root.rstrip("/") + "/services/"
    result = set()
    for key in keys or []:
        if not key.startswith(prefix):
            continue
        parts = key[len(prefix):].split("/")
        if len(parts) >= 5 and parts[1] == "groups" and parts[3] == "instances":
            result.add((parts[0], parts[2], parts[4]))
    return result


def pool_values(prom):
    """Ряды метрик по инстансам: {инстанс: {метрика: {признак: значение}}}; None — сборщик не ответил."""
    url = f"{prom}/api/v1/query?{urllib.parse.urlencode({'query': QUERY})}"
    try:
        rows = fetch_json(url)["data"]["result"]
    except (urllib.error.URLError, OSError, json.JSONDecodeError, KeyError) as e:
        print(f"сборщик метрик не ответил ({prom}): {e}")
        return None
    values = {}
    for row in rows:
        labels = row.get("metric", {})
        node, name = labels.get("node"), labels.get("__name__")
        if not node or not name:
            continue
        try:
            value = float(row["value"][1])
        except (KeyError, IndexError, TypeError, ValueError):
            continue
        values.setdefault(node, {}).setdefault(name, {})[labels.get("reason", "")] = value
    return values


def gate_and_reason(metrics, instance):
    """(готовность, причина) по рядам гейта и причины; причина — ряд со значением 1."""
    per_metric = (metrics or {}).get(instance, {})
    gate = per_metric.get(GATE, {})
    if not gate:
        return "н/д", None
    if any(v == 1 for v in gate.values()):
        return "UP", None
    reason = next((r for r, v in per_metric.get(REASON, {}).items() if v == 1), None)
    return "DOWN", reason


def hikari_path(root, service, group, instance):
    return f"{root.rstrip('/')}/services/{service}/groups/{group}/instances/{instance}/hikari/"


def one(service, group, instance, root, values, keys):
    path = hikari_path(root, service, group, instance)
    if keys is None:
        stored = "н/д"
    else:
        stored = str(sum(1 for k in keys if k.startswith(path)))

    numbers = (values or {}).get(instance, {})

    def num(name):
        series = numbers.get(name)
        if not series:
            return "?"
        # у рядов без признаков (числа пула) в словаре один ключ "" со значением
        return str(int(series.get("", 0)))

    status, reason = gate_and_reason(values, instance)
    extra = f"  reason={reason}" if reason else ""
    print(f"{instance:28s} ready={status:5s} max={num('pool_maximum_pool_size'):>3} "
          f"total={num('pool_connections_open'):>3} active={num('pool_connections_busy'):>3} "
          f"idle={num('pool_connections_idle'):>3} keys={stored:>3}  {path}{extra}")


def main():
    if len(sys.argv) < 2:
        print("usage: pool-all.py <root-prefix> [prom-url]", file=sys.stderr)
        return 2
    prom = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:9090"
    root = sys.argv[1]

    keys, _values = read_snapshot()
    instances = set(fleet())
    if keys is not None:
        instances |= registered_instances(keys, root)
    if not instances:
        print("  (состава нет: docker compose ps пуст и узлов регистрации нет — стенд не поднят?)")
        return 0

    values = pool_values(prom)
    for service, group, instance in sorted(instances):
        one(service, group, instance, root, values, keys)
    return 0


if __name__ == "__main__":
    sys.exit(main())