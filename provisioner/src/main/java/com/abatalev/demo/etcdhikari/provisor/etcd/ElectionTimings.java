package com.abatalev.demo.etcdhikari.provisor.etcd;

import java.time.Duration;

/**
 * Чистые расчёты таймингов выборов лидера.
 *
 * <p>Keepalive аренды выборов идёт с интервалом TTL/3 — как у аренды регистрации инстансов
 * (см. {@code EtcdInstanceRegistration} у сервиса): при трёх пропущенных keepalive аренда ещё
 * живёт по TTL, и лидер успевает об этом узнать до потери лидерства. Минимум — 1 секунда.
 */
public final class ElectionTimings {

    private ElectionTimings() {
    }

    /** Интервал keepalive аренды выборов: TTL/3, но не меньше 1 секунды. */
    public static long keepaliveIntervalMillis(Duration ttl) {
        return Math.max(1, ttl.getSeconds() / 3) * 1000L;
    }
}