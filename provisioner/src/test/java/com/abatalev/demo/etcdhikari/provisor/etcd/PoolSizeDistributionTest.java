package com.abatalev.demo.etcdhikari.provisor.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Чистое распределение бюджета — главная арифметика провижинера, покрыта юнит-тестами целиком
 * (воркер и watch-цикл проверяются на живом стенде, Testcontainers в проекте нет).
 */
class PoolSizeDistributionTest {

    /** Верхняя граница доли — по умолчанию зеркалит HikariSettings.POOL_SIZE_MAX. */
    private static final int MAX_SHARE = 200;

    private static List<String> nodes(int count) {
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            keys.add("/config/services/a/groups/g/instances/i" + i + "/");
        }
        return keys;
    }

    private static List<Integer> sizesOf(PoolSizeDistribution.Result result) {
        return result.shares().stream().map(PoolSizeDistribution.Share::size).toList();
    }

    @Test
    void splitsEvenlyWithRemainderToFirst() {
        var result = PoolSizeDistribution.distribute(100, 1, MAX_SHARE, nodes(3));
        assertFalse(result.refused());
        assertEquals(List.of(34, 33, 33), sizesOf(result));
        assertEquals(100, result.total());
        assertEquals(0, result.excess());
    }

    @Test
    void exactSplitWithoutRemainder() {
        var result = PoolSizeDistribution.distribute(90, 1, MAX_SHARE, nodes(3));
        assertEquals(List.of(30, 30, 30), sizesOf(result));
        assertEquals(90, result.total());
    }

    @Test
    void singleInstanceTakesWholeBudget() {
        var result = PoolSizeDistribution.distribute(100, 1, MAX_SHARE, nodes(1));
        assertEquals(List.of(100), sizesOf(result));
        assertEquals(0, result.excess());
    }

    @Test
    void minimumDoesNotInterfereWhenBudgetCoversEveryone() {
        // n*m = 6*15 = 90 ≤ 100 — каждый получает не меньше минимума, избытка нет.
        var result = PoolSizeDistribution.distribute(100, 15, MAX_SHARE, nodes(6));
        assertFalse(result.refused());
        assertEquals(0, result.excess());
        assertEquals(100, result.total());
        for (PoolSizeDistribution.Share share : result.shares()) {
            assertTrue(share.size() >= 15, "доля " + share.size() + " меньше минимума 15");
        }
    }

    @Test
    void servesOnlyWhatBudgetAllowsWhenMinimumDoesNotFit() {
        // serve = min(3, 100 div 40) = 2 → двое по 50, третий избыток.
        var result = PoolSizeDistribution.distribute(100, 40, MAX_SHARE, nodes(3));
        assertFalse(result.refused());
        assertEquals(List.of(50, 50), sizesOf(result));
        assertEquals(100, result.total());
        assertEquals(1, result.excess());
    }

    @Test
    void minimumOfOneServesEveryone() {
        // Обратная совместимость с прежней политикой: ограничения снизу нет.
        var result = PoolSizeDistribution.distribute(8, 1, MAX_SHARE, nodes(8));
        assertEquals(0, result.excess());
        assertEquals(8, result.total());
        assertEquals(List.of(1, 1, 1, 1, 1, 1, 1, 1), sizesOf(result));
    }

    @Test
    void refusesWhenBudgetBelowMinimum() {
        var result = PoolSizeDistribution.distribute(30, 40, MAX_SHARE, nodes(4));
        assertTrue(result.refused());
        assertEquals(List.of(), result.shares());
        assertEquals(4, result.excess());
        assertTrue(result.refusal().contains("меньше минимальной доли"), result.refusal());
    }

    @Test
    void refusesWhenMinimumAboveShareCeiling() {
        var result = PoolSizeDistribution.distribute(1000, 500, MAX_SHARE, nodes(2));
        assertTrue(result.refused());
        assertTrue(result.refusal().contains("выше верхней границы"), result.refusal());
    }

    @Test
    void refusesNonPositiveValues() {
        assertTrue(PoolSizeDistribution.distribute(0, 1, MAX_SHARE, nodes(2)).refused());
        assertTrue(PoolSizeDistribution.distribute(-1, 1, MAX_SHARE, nodes(2)).refused());
        assertTrue(PoolSizeDistribution.distribute(100, 0, MAX_SHARE, nodes(2)).refused());
        assertTrue(PoolSizeDistribution.distribute(100, -3, MAX_SHARE, nodes(2)).refused());
    }

    @Test
    void clampsShareAboveCeiling() {
        // 4 инстанса по 250 при бюджете 1000 — сервис 250 не примет, усекаем до 200.
        var result = PoolSizeDistribution.distribute(1000, 1, MAX_SHARE, nodes(4));
        assertFalse(result.refused());
        assertEquals(List.of(200, 200, 200, 200), sizesOf(result));
        assertEquals(800, result.total());
    }

    @Test
    void emptyServiceIsNotARefusal() {
        var result = PoolSizeDistribution.distribute(100, 40, MAX_SHARE, List.of());
        assertFalse(result.refused());
        assertEquals(List.of(), result.shares());
        assertEquals(0, result.excess());
    }

    @Test
    void sharesFollowGivenOrder() {
        var result = PoolSizeDistribution.distribute(10, 1, MAX_SHARE,
                List.of("/z/", "/a/", "/m/"));
        assertEquals("/z/", result.shares().get(0).nodeKey());
        assertEquals("/a/", result.shares().get(1).nodeKey());
        assertEquals("/m/", result.shares().get(2).nodeKey());
        // Остаток достаётся первому в порядке, а не по имени.
        assertEquals(List.of(4, 3, 3), sizesOf(result));
    }

    @Test
    void totalNeverExceedsBudget() {
        // Инвариант потолка на широком наборе: сумма долей не превышает бюджет ни при каких m.
        for (int n = 1; n <= 12; n++) {
            for (int budget = 1; budget <= 60; budget++) {
                for (int min = 1; min <= 20; min++) {
                    var result = PoolSizeDistribution.distribute(budget, min, MAX_SHARE, nodes(n));
                    if (!result.refused()) {
                        assertTrue(result.total() <= budget,
                                "n=" + n + " N=" + budget + " m=" + min + " сумма=" + result.total());
                        assertEquals(n, result.shares().size() + result.excess());
                    }
                }
            }
        }
    }
}
