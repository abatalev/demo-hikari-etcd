package com.abatalev.demo.etcdhikari.service.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Размер пула — ровно то, что приходит из etcd ({@code maximumPoolSize} и {@code minimumIdle}).
 *
 * <p>Отдельный тип, потому что время жизни у него одно: событие конфигурации. Всё остальное —
 * цель соединения и таймауты — задано локально на весь процесс и меняется только перезапуском,
 * поэтому в etcd его нет и быть не может.
 *
 * <p>Nullable-поля = «не задано»: {@link #normalize()} подставляет нулевой пул вместо незаданного
 * максимума и ведёт минимум за максимумом.
 */
public record PoolSize(Integer maximumPoolSize, Integer minimumIdle) {

    /**
     * Верхняя граница размера пула. Нижняя граница локального значения — 0 (пула нет: размер
     * приходит только из etcd, а 0 из хранилища отклоняется источником до normalize — см.
     * {@code EtcdPoolConfigSource.apply}).
     */
    public static final int POOL_SIZE_MAX = 200;

    /**
     * Валидация + мягкая нормализация. Жёсткие нарушения -> исключение (конфиг отклоняется,
     * пул остаётся на последнем рабочем размере). Мягкие -> warning + починка.
     */
    public Normalized normalize() {
        List<String> warnings = new ArrayList<>();

        int max = maximumPoolSize == null ? 0 : intInRange(maximumPoolSize, "maximumPoolSize", 0, POOL_SIZE_MAX);
        int minIdle = minimumIdle == null ? max : intInRange(minimumIdle, "minimumIdle", 0, POOL_SIZE_MAX);
        if (minIdle > max) {
            warnings.add("minimumIdle=" + minIdle + " > maximumPoolSize=" + max + " -> понижен до " + max);
            minIdle = max;
        }

        return new Normalized(new PoolSize(max, minIdle), warnings);
    }

    public record Normalized(PoolSize size, List<String> warnings) {}

    /** Список изменений вида "maximumPoolSize: 10 -> 20" для логов и признаков трассы. */
    public List<String> diff(PoolSize other) {
        List<String> out = new ArrayList<>();
        addDiff(out, "maximumPoolSize", maximumPoolSize, other.maximumPoolSize);
        addDiff(out, "minimumIdle", minimumIdle, other.minimumIdle);
        return out;
    }

    private static void addDiff(List<String> out, String field, Integer from, Integer to) {
        if (!Objects.equals(from, to)) {
            out.add(field + ": " + from + " -> " + to);
        }
    }

    private static int intInRange(Integer value, String field, int min, int max) {
        if (value < min || value > max) {
            throw new InvalidSettingsException(field + "=" + value + " вне диапазона [" + min + ".." + max + "]");
        }
        return value;
    }
}