#!/usr/bin/env python3
"""Бэкенды распределителя по инстансам сервисов (динамическая конфигурация Traefik).

Аргументы:
  1) кортежи ETCD_INSTANCES через пробел: `service|group|instance|port`
  2) путь файла, который нужно записать (по умолчанию stdout)

На каждый сервис стенда — своя точка входа и свой маршрут, так что трафик к одному сервису не может
уйти к инстансам другого: маршрут привязан к точке входа, а не выбирается из общего списка. Именно
поэтому одного файла на стенд достаточно — разделение по сервису в привязке маршрута, а не в том,
что каждый распределитель читает свой файл.

Адрес бэкенда — имя инстанса плюс порт контейнера: имя инстанса одновременно является именем
сервиса в compose, поэтому список бэкендов выводится из ETCD_INSTANCES и четвёртая копия списка
инстансов в репозитории не появляется.

Признак готовности, который опрашивает распределитель, — тот же, на котором держится гейт трафика:
/actuator/health/readiness. Поэтому «не готов» для распределителя и «не обслуживает» для клиента —
это один и тот же факт, а не две оценки, которые могут разойтись. Период опроса задан здесь и
одновременно является контрактом из спеки: распределитель SHALL исключать неготовый экземпляр не
позднее одного периода опроса. Правка интервала — правка требования, а не тюнинг.

Опрос объявлен на самом балансировщике каждого сервиса, а не на каждом сервере: во второй редакции
Traefik признака на сервере не знает, и конфигурация с ним целиком не грузится. Путь общий для всех
серверов сервиса, и это правильно — признак готовности у всех инстансов один.

YAML печатается вручную, без PyYAML: стенд объявляет инструментами docker, make, curl и python3,
и добавление библиотеки означало бы новую зависимость ради файла в несколько десятков строк.

Файл зафиксирован в репозитории (traefik/dynamic/fleet.yml), чтобы стенд поднимался даже без
запуска make. Расхождение с ETCD_INSTANCES ловит `make check-balancers`, а `make up`
перегенерирует файл перед стартом.
"""
import sys

# Период опроса готового сервера и таймаут одного опроса.
HEALTH_INTERVAL = "1s"
HEALTH_TIMEOUT = "500ms"
# Опрашивается признак готовности, а не корень: /api/work отвечает 503 при закрытом гейте,
# и тогда «не готов» означал бы ровно то, что опрашивать и не нужно было.
HEALTH_PATH = "/actuator/health/readiness"
# Порт, который слушает экземпляр внутри контейнера.
CONTAINER_PORT = "8080"


def entry_point_of(service):
    """Имя точки входа для сервиса: `service-a` → `web-a`.

    Имя точки обязано совпасть с одноимённым блоком в traefik/traefik.yml. Расхождение не
    приводит к отказу — Traefik просто не создаст маршрут, и точка входа будет отдавать 404, —
    поэтому `make check-balancers` сверяет и эту часть.
    """
    return "web-" + service.split("-")[-1]


def instances_by_service(tuples):
    """Инстансы, сгруппированные по сервису, в порядке исходного списка: так ротация детерминирована."""
    grouped = {}
    for t in tuples:
        parts = t.split("|")
        if len(parts) != 4:
            print(f"пропущен битый кортеж: {t}", file=sys.stderr)
            continue
        grouped.setdefault(parts[0], []).append(parts[2])
    return grouped


def render(grouped):
    """Печатает динамическую конфигурацию Traefik: по точке входа на сервис стенда."""
    out = ["http:", "  routers:"]
    for service in sorted(grouped):
        out.append(f"    {service}:")
        out.append("      entryPoints:")
        out.append(f"        - {entry_point_of(service)}")
        out.append("      rule: PathPrefix(`/`)")
        out.append(f"      service: {service}")
    out.append("  services:")
    for service in sorted(grouped):
        out.append(f"    {service}:")
        out.append("      loadBalancer:")
        out.append("        healthCheck:")
        out.append(f"          path: {HEALTH_PATH}")
        out.append(f"          interval: {HEALTH_INTERVAL}")
        out.append(f"          timeout: {HEALTH_TIMEOUT}")
        out.append("        servers:")
        for instance in grouped[service]:
            out.append(f"        - url: http://{instance}:{CONTAINER_PORT}")
    return "\n".join(out) + "\n"


def main():
    if len(sys.argv) < 2:
        print("usage: balancers.py '<tuples>' [out-file]", file=sys.stderr)
        return 1
    grouped = instances_by_service(sys.argv[1].split())
    if not grouped:
        print("в ETCD_INSTANCES нет ни одного корректного кортежа", file=sys.stderr)
        return 1
    text = render(grouped)
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())