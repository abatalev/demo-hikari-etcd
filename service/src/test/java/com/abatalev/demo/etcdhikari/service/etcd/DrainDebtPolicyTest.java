package com.abatalev.demo.etcdhikari.service.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DrainDebtPolicyTest {

    /** Квант по умолчанию: публикуется каждое изменение величины. */
    private static final int QUANTUM_OFF = 0;

    /** Потолок прежний, квант выключен: публикация идёт по изменению величины. */
    private static Integer byChange(int debt, Integer last) {
        return DrainDebtPolicy.toPublish(debt, last, false, false, QUANTUM_OFF);
    }

    /** Потолок прежний, квант задан. */
    private static Integer byChange(int debt, Integer last, int quantum) {
        return DrainDebtPolicy.toPublish(debt, last, false, false, quantum);
    }

    /** Потолок изменился: публикуем всегда, квант выключен. */
    private static Integer byCeiling(int debt, Integer last) {
        return DrainDebtPolicy.toPublish(debt, last, true, false, QUANTUM_OFF);
    }

    /** Потолок изменился, квант задан. */
    private static Integer byCeiling(int debt, Integer last, int quantum) {
        return DrainDebtPolicy.toPublish(debt, last, true, false, quantum);
    }

    @Test
    @DisplayName("первая ненулевая публикация проходит всегда: иначе провижёр не увидит долга")
    void firstNonZeroAlwaysPublished() {
        assertEquals(Integer.valueOf(25), byChange(25, null));
        assertEquals(Integer.valueOf(1), byChange(1, null));
    }

    @Test
    @DisplayName("терминальный ноль проходит всегда, даже если ноль уже публиковался")
    void terminalZeroAlwaysPublished() {
        assertEquals(Integer.valueOf(0), byChange(0, 5));
        assertEquals(Integer.valueOf(0), byChange(0, null));
        // Повторный ноль не пишется: публикация по изменению, а не по расписанию.
        assertNull(byChange(0, 0));
    }

    @Test
    @DisplayName("ноль проходит и при ненулевом кванте: разблокировка роста не фильтруется")
    void terminalZeroIgnoresQuantum() {
        assertEquals(Integer.valueOf(0), byChange(0, 3, 100));
    }

    @Test
    @DisplayName("смена потолка публикуется даже при неизменной величине")
    void ceilingChangeIsPublishedEvenIfValueUnchanged() {
        // Сжатие освободилось мгновенно: величина та же, что была, но потолок другой. Молчание
        // оставило бы провижёра на худшем случае и заморозило флот ниже бюджета.
        assertEquals(Integer.valueOf(0), byCeiling(0, 0));
        assertEquals(Integer.valueOf(25), byCeiling(25, 25));
    }

    @Test
    @DisplayName("смена потолка игнорирует и квант: подтверждение важнее фильтра")
    void ceilingChangeIgnoresQuantum() {
        assertEquals(Integer.valueOf(1), byCeiling(1, 25, 1000));
    }

    @Test
    @DisplayName("неизменная величина не публикуется: публикация по изменению, а не по таймеру")
    void unchangedNotPublished() {
        assertNull(byChange(7, 7));
    }

    @Test
    @DisplayName("промежуточные значения проходят при выключенном кванте")
    void everyChangePublishedWhenQuantumOff() {
        assertEquals(Integer.valueOf(24), byChange(24, 25));
        assertEquals(Integer.valueOf(1), byChange(1, 2));
    }

    @Test
    @DisplayName("квант пропускает мелкие шаги, но не пропускает крупные")
    void quantumSkipsSmallSteps() {
        // Опубликовано 25, шаг 10: 20 и 16 мельче кванта, 15 и 10 — нет.
        assertNull(byChange(20, 25, 10));
        assertNull(byChange(16, 25, 10));
        assertEquals(Integer.valueOf(15), byChange(15, 25, 10));
        assertEquals(Integer.valueOf(10), byChange(10, 25, 10));
    }

    @Test
    @DisplayName("квант пропускает и в сторону увеличения долга")
    void quantumAppliesToGrowingDebt() {
        // Долг вырос: 27 — шаг 2 мельче кванта, 40 — шаг 15, проходит.
        assertNull(byChange(27, 25, 10));
        assertEquals(Integer.valueOf(40), byChange(40, 25, 10));
    }

    @Test
    @DisplayName("отрицательное значение трактуется как освобождение: публикуется ноль")
    void negativeDebtBecomesZero() {
        assertEquals(Integer.valueOf(0), byChange(-3, 5));
    }

    @Test
    @DisplayName("отрицательное значение не публикуется повторно, если ноль уже был")
    void negativeDebtAfterZeroIsSilent() {
        assertNull(byChange(-3, 0));
    }

    @Test
    @DisplayName("монотонное освобождение до нуля проходит без пропусков при выключенном кванте")
    void releaseToZeroPublishesEveryStep() {
        Integer last = byChange(5, null);
        assertEquals(Integer.valueOf(5), last);
        for (int debt = 4; debt > 0; debt--) {
            Integer next = byChange(debt, last);
            assertEquals(Integer.valueOf(debt), next);
            last = next;
        }
        assertEquals(Integer.valueOf(0), byChange(0, last));
    }

    @Test
    @DisplayName("с квантом освобождение доходит до нуля: нулевой шаг не блокирует разблокировку")
    void releaseToZeroWithQuantumStillUnblocks() {
        // Квант 10, долг дошёл до 5: шаг 5 мельче кванта, но ноль пройдёт.
        assertNull(byChange(5, 10, 10));
        assertEquals(Integer.valueOf(0), byChange(0, 10, 10));
    }

    @Test
    @DisplayName("сжатие позже последнего нуля всё равно публикуется и снимается")
    void debtAfterTerminalZeroStillUnblocks() {
        // Обратный порядок величин: терминальный ноль был опубликован раньше, чем началось
        // сжатие. И появление долга, и его снятие обязаны дойти, иначе провижёр либо выдаст
        // освобождаемое место, либо не разблокирует рост.
        assertEquals(Integer.valueOf(25), byChange(25, 0));
        assertEquals(Integer.valueOf(0), byChange(0, 25));
    }
}
