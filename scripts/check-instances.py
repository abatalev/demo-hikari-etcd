#!/usr/bin/env python3
"""Живая сверка состава: у каждого инстанса `docker compose ps` есть ряды в сборщике.

Аргумент: адрес сборщика метрик (по умолчанию http://127.0.0.1:9090).

Перечень — `docker compose ps` (scripts/fleet.py), а не конфигурация: число реплик группы
задаётся `--scale`, и состав объявляет сам compose. Сверка точечная, мгновенным запросом
ряда состояния пула по каждому инстансу: у процесса ряд есть всегда (гейдж регистрируется
в конструкторе и существует и при снятом пуле, отдавая ноль), поэтому отсутствие ряда
означает «процесса нет в наблюдении», а не «конфигурации нет».

Перечень значений признака для сверки не годится: он отдаёт всё, что сборщик видел за окно
хранения, и остановившийся час назад инстанс в нём остаётся. Мгновенный запрос смотрит
текущее состояние: ряд, пропавший из выдачи двери, сборщик помечает устаревшим на
следующем опросе, и запрос его уже не отдаёт (на стенде от остановки инстанса до
срабатывания сверки проходит около 40 секунд).

Ненулевой код возврата — расхождение поимённо: строки вида `a1b2c3d4e5f6: рядов нет`.
Отличать «нет рядов» от «инстанс ещё не успел отправить» нельзя — оба означают одно и то же
для наблюдения.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

from fleet import fleet

MARKER = "pool_maximum_pool_size"


def collected_nodes(prom):
    """Множество имён инстансов, у которых сборщик видит ряд состояния пула; None — сборщик не ответил."""
    url = f"{prom}/api/v1/query?{urllib.parse.urlencode({'query': MARKER})}"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            rows = json.load(r)["data"]["result"]
    except (urllib.error.URLError, OSError, json.JSONDecodeError, KeyError) as e:
        print(f"сборщик метрик не ответил ({prom}): {e}")
        return None
    nodes = set()
    for row in rows:
        node = row.get("metric", {}).get("node")
        if node:
            nodes.add(node)
    return nodes


def main():
    prom = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:9090"

    instances = fleet()
    if not instances:
        print("состав пуст: docker compose ps не показывает ни одного инстанса (стенд не поднят?)",
              file=sys.stderr)
        return 2

    nodes = collected_nodes(prom)
    if nodes is None:
        return 2

    missing = [(s, g, i) for s, g, i in instances if i not in nodes]
    if not missing:
        print(f"состав совпадает с наблюдением: {len(instances)} инстанс(ов), "
              f"у каждого есть ряд {MARKER}")
        return 0

    print(f"нет рядов {MARKER} у {len(missing)} из {len(instances)} инстанс(ов):")
    for service, group, instance in missing:
        print(f"  {instance} ({service}/{group})")
    print("инстанс мог быть остановлен или не успел отправить метрики; "
          "сверка по спискам: make pool")
    return 1


if __name__ == "__main__":
    sys.exit(main())