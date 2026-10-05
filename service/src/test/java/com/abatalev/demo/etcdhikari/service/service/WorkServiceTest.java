package com.abatalev.demo.etcdhikari.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.abatalev.demo.etcdhikari.service.dao.DemoItemsDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Сценарий нагрузочной точки проверяется без БД: подменяем dao и смотрим, в каком порядке идут
 * вызовы. Порядок здесь — часть измеряемой величины: счёт строк обязан быть до сна, иначе его
 * время попадёт в {@code dbMs} и перцентили нагрузчика поедут.
 */
class WorkServiceTest {

    @Test
    @DisplayName("счёт строк идёт до сна в базе")
    void countHappensBeforeSleep() {
        DemoItemsDao dao = mock(DemoItemsDao.class);
        when(dao.countItems()).thenReturn(2000);

        WorkService.Measurement m = new WorkService(dao).run(20, true);

        InOrder order = inOrder(dao);
        order.verify(dao).countItems();
        order.verify(dao).sleep(20L);
        assertThat(m.rows()).isEqualTo(2000);
    }

    @Test
    @DisplayName("без флага счёта счёт не выполняется, строки не возвращаются")
    void countSkippedWhenNotRequested() {
        DemoItemsDao dao = mock(DemoItemsDao.class);

        WorkService.Measurement m = new WorkService(dao).run(20, false);

        verify(dao).sleep(20L);
        verify(dao, never()).countItems();
        assertThat(m.rows()).isNull();
    }

    @Test
    @DisplayName("измерение неотрицательно, ожидание не больше общего времени")
    void measurementIsNonNegative() {
        DemoItemsDao dao = mock(DemoItemsDao.class);
        when(dao.countItems()).thenReturn(2000);

        WorkService.Measurement m = new WorkService(dao).run(0, true);

        assertThat(m.durationMs()).isNotNegative();
        assertThat(m.dbMs()).isNotNegative();
        assertThat(m.queueWaitMs()).isNotNegative().isLessThanOrEqualTo(m.durationMs());
    }

    @Test
    @DisplayName("нулевой сон передаётся в dao как есть — решает уже dao")
    void zeroSleepIsDelegatedAsIs() {
        DemoItemsDao dao = mock(DemoItemsDao.class);

        new WorkService(dao).run(0, false);

        verify(dao).sleep(0L);
        verifyNoMoreInteractions(dao);
    }
}