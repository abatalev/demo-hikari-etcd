package com.abatalev.demo.etcdhikari.service.management.pool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Неосвобождённое сжатие — единственная величина, которой инстанс отвечает провижёру о том,
 * сколько он держит сверх потолка. Покрыта чистая функция: сам пул и цикл дренажа требуют БД и
 * проверяются на стенде.
 */
class ManagedPoolDebtTest {

    @Test
    @DisplayName("долг равен удерживаемому минус потолок")
    void debtIsHeldAboveCeiling() {
        assertThat(ManagedPool.unreleasedConnections(20, 25)).isEqualTo(5);
        assertThat(ManagedPool.unreleasedConnections(20, 21)).isEqualTo(1);
    }

    @Test
    @DisplayName("пока инстанс держит не больше потолка, долг нулевой")
    void noDebtAtOrBelowCeiling() {
        assertThat(ManagedPool.unreleasedConnections(25, 25)).isZero();
        assertThat(ManagedPool.unreleasedConnections(25, 10)).isZero();
    }

    @Test
    @DisplayName("без конфигурации весь удерживаемый счёт долгом: потолок нулевой")
    void withoutConfigEverythingIsDebt() {
        // Пул закрыт, дренаж идёт, а потолок в конфигурации уже 0 — освобождать нечего.
        assertThat(ManagedPool.unreleasedConnections(0, 25)).isEqualTo(25);
    }

    @Test
    @DisplayName("долг неотрицателен: ошибка оценки односторонняя")
    void debtIsNeverNegative() {
        // Отчёт занижен быть не может по построению, а завышение лишь придержало бы рост флота.
        assertThat(ManagedPool.unreleasedConnections(25, 0)).isZero();
        assertThat(ManagedPool.unreleasedConnections(0, 0)).isZero();
    }

    @Test
    @DisplayName("освобождение уменьшает долг монотонно и обнуляет его в ноль")
    void debtFallsToZeroAsConnectionsReturn() {
        int[] steps = {25, 24, 20, 10, 5, 1, 0};
        int previous = ManagedPool.unreleasedConnections(20, steps[0]);
        for (int held : java.util.Arrays.stream(steps).skip(1).toArray()) {
            int debt = ManagedPool.unreleasedConnections(20, held);
            assertThat(debt).isLessThanOrEqualTo(previous);
            assertThat(debt).isGreaterThanOrEqualTo(0);
            previous = debt;
        }
        assertThat(previous).isZero();
    }

    @Test
    @DisplayName("две величины в одном отчёте неотрицательны при любом сочетании потолков")
    void bothGenerationsCountedWithoutDoubleDipping() {
        // Текущее поколение держит 12 при потолке 10, освобождаемое — 13: удерживается 25.
        int held = 12 + 13;
        assertThat(ManagedPool.unreleasedConnections(10, held)).isEqualTo(15);
    }
}
