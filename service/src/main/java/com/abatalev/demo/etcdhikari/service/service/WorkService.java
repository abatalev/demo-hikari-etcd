package com.abatalev.demo.etcdhikari.service.service;

import com.abatalev.demo.etcdhikari.service.dao.DemoItemsDao;
import org.springframework.stereotype.Component;

/**
 * Сценарий нагрузочной точки: взять соединение из пула, выполнить запрос, поспать в базе и
 * измерить, сколько из общего времени ушло на базу и сколько — на ожидание свободного соединения.
 *
 * <p>Порядок шагов и границы таймеров — часть измеряемой величины, а не оформление: счёт строк идёт
 * <em>до</em> отметки {@code dbStart}, поэтому его время попадает в общее время и в ожидание
 * соединения, но не в время в базе. Сдвинуть эту границу — значит поедут перцентили нагрузчика.
 */
@Component
public class WorkService {

    private final DemoItemsDao items;

    public WorkService(DemoItemsDao items) {
        this.items = items;
    }

    /** Измерение одного замера. Признаков успеха и текста ошибки здесь нет — это дело транспорта. */
    public record Measurement(long durationMs, long dbMs, long queueWaitMs, Integer rows) {}

    /**
     * @param ms сколько миллисекунд держать соединение в базе (0 — не удерживать)
     * @param countRows считать ли строки демо-таблицы
     */
    public Measurement run(long ms, boolean countRows) {
        long startNanos = System.nanoTime();
        Integer rows = countRows ? items.countItems() : null;
        long dbStart = System.nanoTime();
        items.sleep(ms);
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new Measurement(totalMs, dbMs, Math.max(0, totalMs - dbMs), rows);
    }
}