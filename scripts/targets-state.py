#!/usr/bin/env python3
"""Состояние целей сбора метрик из API сборщика (вывод `curl /api/v1/targets?state=active`).

Аргументы:
  1) [что показать, если целей нет: `all` (по умолчанию) или `down`]

Печатает по строке на цель: задача, адрес, состояние и текст последней ошибки. Наличие недоступных
целей не является ошибкой самого скрипта — решение принимает вызывающая сторона (`make up` лишь
сообщает, `make targets` возвращает ненулевой код).
"""
import json
import sys


def main():
    show = sys.argv[1] if len(sys.argv) > 1 else "all"
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError) as e:
        print(f"ответ сборщика не разобран: {e}")
        return 2

    targets = data.get("data", {}).get("activeTargets", [])
    if not targets:
        print("сборщик не знает ни одной цели")
        return 1

    down = 0
    for t in sorted(targets, key=lambda t: (t.get("labels", {}).get("job", ""),
                                             t.get("labels", {}).get("instance", ""))):
        health = t.get("health", "?")
        if health != "up":
            down += 1
            if show == "down":
                continue
        labels = t.get("labels", {})
        error = (t.get("lastError") or "").strip()
        print(f"{health:>6}  {labels.get('job', '?'):<12} {labels.get('instance', '?'):<32}"
              f"{error}")

    print(f"всего целей: {len(targets)}, недоступно: {down}")
    return 0


if __name__ == "__main__":
    sys.exit(main())