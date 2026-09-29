package com.example.provisioner.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Чистый расчёт интервала keepalive аренды выборов (TTL/3, минимум 1с). */
class ElectionTimingsTest {

    @Test
    void keepaliveIsTtlDividedByThree() {
        assertEquals(5_000L, ElectionTimings.keepaliveIntervalMillis(Duration.ofSeconds(15)));
        assertEquals(3_000L, ElectionTimings.keepaliveIntervalMillis(Duration.ofSeconds(10)));
    }

    @Test
    void keepaliveFloorIsOneSecond() {
        assertEquals(1_000L, ElectionTimings.keepaliveIntervalMillis(Duration.ofSeconds(3)));
        assertEquals(1_000L, ElectionTimings.keepaliveIntervalMillis(Duration.ofSeconds(2)));
        assertEquals(1_000L, ElectionTimings.keepaliveIntervalMillis(Duration.ofSeconds(1)));
    }
}