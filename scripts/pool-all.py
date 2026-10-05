#!/usr/bin/env python3
"""Сводка по всем инстансам стенда одной командой: готовность, пул, путь конфигурации.

Аргументы:
  1) кортежи ETCD_INSTANCES через пробел: `service|group|instance|port`
  2) корень в etcd, например `/config` (из него печатается путь конфигурации инстанса)
  3) адрес сборщика метрик (по умолчанию http://127.0.0.1:9090)

На stdin — снимок etcd (`etcdctl get --prefix <root>/services/ -w json`). Он читается, а не
запрашивается по одному инстансу: один вызов etcdctl на весь вывод вместо восьми. Пустой или
нечитаемый stdin — не ошибка: снимок может быть недоступен (etcd лежит), и тогда колонка с числом
ключей печатается как «н/д».

Три источника, у каждого своя роль: числа пула — из метрик (сборщик их не опрашивает, они приходят
в дверь), готовность и причина её закрытия — из признака готовности инстанса, путь и наличие
конфигурации — из etcd. Ни один из них не требует от инстанса отдельной точки наблюдения.
"""
import base64
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

METRICS = ("pool_maximum_pool_size", "pool_minimum_idle",
           "pool_connections_open", "pool_connections_busy", "pool_connections_idle")

# Один запрос на весь флот вместо восьми. Отбор идёт регуляркой по имени метрики, а НЕ через
# `or` из пяти имён: при совпадении по набору признаков (здесь он одинаковый) правая часть `or`
# отбрасывается как уже покрытая левой, и запрос вернул бы только первую метрику — тихо, без ошибки.
# Регулярка в метке имени привязана целиком, поэтому `pool_connections_awaiting` и
# `pool_config_maximum_pool_size` под неё не попадают.
QUERY = '{__name__=~"' + "|".join(METRICS) + '"}'


def fetch_json(url, timeout=15):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def read_snapshot():
    """Ключи etcd из снимка на stdin или None, если снимка нет."""
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return None
    keys = []
    for entry in data.get("kvs", []):
        keys.append(base64.b64decode(entry["key"]).decode("utf-8"))
    return keys


def pool_values(prom):
    """Числа пула по инстансам: {инстанс: {метрика: значение}}; None, если сборщик не ответил."""
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
            values.setdefault(node, {})[name] = float(row["value"][1])
        except (KeyError, IndexError, TypeError, ValueError):
            continue
    return values


def readiness(base):
    """Готовность и причина её закрытия. Телом ответа служит и 503 — он тоже про готовность."""
    url = base + "/actuator/health/readiness"
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            data = json.load(r)
    except urllib.error.HTTPError as e:
        try:
            data = json.load(e)
        except (json.JSONDecodeError, OSError):
            return "?", None
    except (urllib.error.URLError, OSError, json.JSONDecodeError):
        return "н/д", None
    # В группе readiness состав индикаторов лежит под `components` (а детали — под
    # `components.<имя>.details`), тогда как у общего /actuator/health детали на верхнем уровне.
    # Берём оба места: у группы `details` верхнего уровня нет, и при чтении только его причина
    # молча не печаталась бы — ровно та ошибка «почему не видно, что гейт закрыт».
    source = (data.get("components") or {}).get("poolEtcd") or (data.get("details") or {}).get("poolEtcd") or {}
    return data.get("status", "?"), (source.get("details") or source).get("reason")


def hikari_path(root, service, group, instance):
    return f"{root.rstrip('/')}/services/{service}/groups/{group}/instances/{instance}/hikari/"


def one(tuple_text, root, values, keys):
    service, group, instance, port = tuple_text.split("|")
    base = f"http://localhost:{port}"
    status, reason = readiness(base)

    path = hikari_path(root, service, group, instance)
    if keys is None:
        stored = "н/д"
    else:
        stored = str(sum(1 for k in keys if k.startswith(path)))

    numbers = (values or {}).get(instance, {})

    def num(name):
        value = numbers.get(name)
        return "?" if value is None else str(int(value))

    extra = f"  reason={reason}" if reason else ""
    print(f"{instance:28s} ready={status:5s} max={num('pool_maximum_pool_size'):>3} "
          f"total={num('pool_connections_open'):>3} active={num('pool_connections_busy'):>3} "
          f"idle={num('pool_connections_idle'):>3} keys={stored:>3}  {path}{extra}")


def main():
    if len(sys.argv) < 3:
        print("usage: pool-all.py '<tuples>' <root-prefix> [prom-url]", file=sys.stderr)
        return 2
    prom = sys.argv[3] if len(sys.argv) > 3 else "http://127.0.0.1:9090"
    root = sys.argv[2]
    tuples = [t for t in sys.argv[1].split() if t.count("|") == 3]
    if not tuples:
        print("  (нет инстансов в ETCD_INSTANCES)")
        return 0

    values = pool_values(prom)
    keys = read_snapshot()
    for tuple_text in tuples:
        one(tuple_text, root, values, keys)
    return 0


if __name__ == "__main__":
    sys.exit(main())