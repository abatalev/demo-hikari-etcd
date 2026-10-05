#!/usr/bin/env python3
"""Живая сверка состава: у каждого инстанса канонического списка есть ряды в сборщике.

Аргументы:
  1) кортежи ETCD_INSTANCES через пробел: `service|group|instance|port`
  2) адрес сборщика метрик (по умолчанию http://127.0.0.1:9090)

Зачем это вместо сверки списка целей: цели сбора у сборщика больше нет — приложения отправляют
метрики в точку приёма, а сборщик опрашивает только её. Поэтому «инстанс есть в .env» и «инстанс
виден в наблюдении» сверяются напрямую: имя из канонического списка ищется в рядах сборщика.

Ненулевой код возврата означает расхождение поимённо: строки вида `service-a-group-1-1: рядов нет`.
Отличать «нет рядов» от «инстанс ещё не успел отправить» нельзя — оба означают одно и то же для
наблюдения, а сверить состав с готовностью стенда можно через `make pool`.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

MARKER = "pool_config_state"


def instances(tuples_text):
    for t in tuples_text.split():
        parts = t.split("|")
        if len(parts) != 4 or not parts[2]:
            print(f"пропущен битый кортеж: {t}", file=sys.stderr)
            continue
        yield parts[0], parts[1], parts[2]


def collected_nodes(prom):
    """Имена инстансов, чьи ряды есть в сборщике ПРЯМО СЕЙЧАС.

    Именно мгновенный запрос, а не перечень значений признака: перечень меток отдаёт все ряды,
    которые сборщик вообще видел за окно хранения, поэтому остановившийся час назад инстанс в нём
    остаётся. Мгновенный запрос отдаёт ряды, у которых есть свежая точка (или метка устаревания),
    и потому отвечает на вопрос «присылает ли этот инстанс метрики сейчас» — тот же вопрос, который
    задаёт правило молчания, только без ожидания его окна.
    """
    url = f"{prom}/api/v1/query?{urllib.parse.urlencode({'query': MARKER})}"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            rows = json.load(r)["data"]["result"]
    except (urllib.error.URLError, OSError, json.JSONDecodeError, KeyError) as e:
        print(f"сборщик метрик не ответил ({prom}): {e}")
        return None
    return {row["metric"]["node"] for row in rows if "node" in row["metric"]}


def main():
    if len(sys.argv) < 2:
        print("usage: check-instances.py '<tuples>' [prom-url]", file=sys.stderr)
        return 2
    prom = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:9090"

    nodes = collected_nodes(prom)
    if nodes is None:
        return 2

    missing = []
    total = 0
    for service, group, instance in instances(sys.argv[1]):
        total += 1
        if instance not in nodes:
            missing.append((service, group, instance))

    for service, group, instance in missing:
        print(f"нет рядов {MARKER}: {service}/{group}/{instance}")
    print(f"инстансов в списке: {total}, без рядов в наблюдении: {len(missing)}")
    if missing:
        print("список инстансов и наблюдение разошлись; на стенде это либо остановленный "
              "инстанс, либо недоступная точка приёма сигналов (make targets, make pool)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
