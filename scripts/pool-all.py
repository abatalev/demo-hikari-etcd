#!/usr/bin/env python3
"""Сводка по всем инстансам стенда одной командой: готовность, пул, путь конфигурации.

Аргументы — кортежи ETCD_INSTANCES (service|group|instance|hostPort).
"""
import json
import sys
import urllib.request


def fetch(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def one(t):
    inst, port = t.split("|")[2], t.split("|")[3]
    base = f"http://localhost:{port}"
    try:
        data = fetch(base + "/api/pool")
    except Exception as e:
        print(f"{inst:28s} ready= n/a ({e})")
        return
    etcd = data.get("etcd") or {}
    path = etcd.get("path") or "-"
    ready = "?"
    try:
        h = fetch(base + "/actuator/health/readiness")
        ready = h.get("status", "?")
    except Exception:
        ready = "?"
    p = data.get("pool") or {}
    reason = etcd.get("notReadyReason")
    extra = f"  reason={reason}" if reason else ""
    print(f"{inst:28s} ready={ready:5s} max={str(p.get('maximumPoolSize', '?')):>3} "
          f"total={str(p.get('total', '?')):>3} active={str(p.get('active', '?')):>3} "
          f"idle={str(p.get('idle', '?')):>3}  {path}{extra}")


for arg in sys.argv[1:]:
    for t in arg.split():
        one(t)