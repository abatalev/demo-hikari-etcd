#!/usr/bin/env python3
"""Список целей сбора метрик по инстансам сервиса (файл file_sd для сборщика).

Аргументы:
  1) кортежи ETCD_INSTANCES через пробел: `service|group|instance|port`
  2) порт контейнера, который слушает точка метрик (по умолчанию 8080)
  3) путь файла, который нужно записать (по умолчанию stdout)

Адрес цели — имя инстанса плюс порт контейнера: имя инстанса одновременно является именем сервиса в
compose, поэтому список целей выводится из ETCD_INSTANCES и третья копия списка инстансов в
репозитории не появляется. Признаки service/group/node цели не задаются: их и так отдаёт сам
инстанс, а переопределение метки создало бы ряд exported_node при расхождении.

Список содержит только инстансы сервиса: цели сборщиков баз, провизёра и etcd заданы статически в
prometheus/prometheus.yml, потому что их имена в compose не меняются вместе со списком инстансов.

Файл зафиксирован в репозитории (prometheus/targets.json), чтобы сборщик поднимался даже
без запуска make. Расхождение с ETCD_INSTANCES ловит `make check-targets`, а `make up`
перегенерирует файл перед стартом.
"""
import json
import sys


def main():
    if len(sys.argv) < 2:
        print("usage: targets.py '<tuples>' [container-port] [out-file]", file=sys.stderr)
        return 1
    tuples = sys.argv[1].split()
    port = sys.argv[2] if len(sys.argv) > 2 else "8080"
    out_file = sys.argv[3] if len(sys.argv) > 3 else None

    groups = []
    for t in tuples:
        parts = t.split("|")
        if len(parts) != 4:
            print(f"пропущен битый кортеж: {t}", file=sys.stderr)
            continue
        groups.append({"targets": [f"{parts[2]}:{port}"]})

    text = json.dumps(groups, indent=2) + "\n"
    if out_file:
        with open(out_file, "w", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())