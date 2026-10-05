package com.abatalev.demo.etcdhikari.service.pool;

import java.util.ArrayList;
import java.util.List;

/**
 * Эффективные настройки пула. Nullable-поля = "не задано", значение берётся из дефолтов
 * ({@link #resolve(HikariSettings)}) либо выводится (см. minimumIdle в {@link #normalize()}).
 *
 * <p>Делится на две группы:
 * <ul>
 *   <li>меняемые на лету через {@code HikariConfigMXBean}: maximumPoolSize, minimumIdle,
 *       connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, validationTimeoutMs,
 *       leakDetectionThresholdMs;</li>
 *   <li>требующие пересоздания пула: jdbcUrl, username, password, poolName.</li>
 * </ul>
 */
public record HikariSettings(
        String jdbcUrl,
        String username,
        String password,
        String poolName,
        Integer maximumPoolSize,
        Integer minimumIdle,
        Long connectionTimeoutMs,
        Long idleTimeoutMs,
        Long maxLifetimeMs,
        Long validationTimeoutMs,
        Long leakDetectionThresholdMs) {

    public static final int POOL_SIZE_MIN = 1;
    /**
     * Верхняя граница размера пула. Нижняя граница локального дефолта — 0 (пула нет: размер
     * приходит только из etcd, а 0 из хранилища отклоняется источником до resolve — см.
     * {@code EtcdPoolConfigSource.apply}), поэтому валидация максимума идёт на [0..POOL_SIZE_MAX],
     * а {@link #POOL_SIZE_MIN} остаётся смысловой границей «живого» пула.
     */
    public static final int POOL_SIZE_MAX = 200;

    private static final long CONNECTION_TIMEOUT_MIN_MS = 250;
    private static final long CONNECTION_TIMEOUT_MAX_MS = 600_000;
    private static final long IDLE_TIMEOUT_MIN_MS = 10_000;
    private static final long IDLE_TIMEOUT_MAX_MS = 3_600_000;
    private static final long MAX_LIFETIME_MIN_MS = 30_000;
    private static final long MAX_LIFETIME_MAX_MS = 3_600_000;
    private static final long VALIDATION_TIMEOUT_MIN_MS = 250;
    private static final long VALIDATION_TIMEOUT_MAX_MS = 60_000;
    private static final long LEAK_DETECTION_MIN_MS = 2_000;
    private static final long LEAK_DETECTION_MAX_MS = 600_000;

    /** Подставляет дефолты вместо незаданных (null) полей. */
    public HikariSettings resolve(HikariSettings defaults) {
        return new HikariSettings(
                first(jdbcUrl, defaults.jdbcUrl),
                first(username, defaults.username),
                first(password, defaults.password),
                first(poolName, defaults.poolName),
                first(maximumPoolSize, defaults.maximumPoolSize),
                first(minimumIdle, defaults.minimumIdle),
                first(connectionTimeoutMs, defaults.connectionTimeoutMs),
                first(idleTimeoutMs, defaults.idleTimeoutMs),
                first(maxLifetimeMs, defaults.maxLifetimeMs),
                first(validationTimeoutMs, defaults.validationTimeoutMs),
                first(leakDetectionThresholdMs, defaults.leakDetectionThresholdMs));
    }

    private static <T> T first(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Валидация + мягкая нормализация. Жёсткие нарушения -> исключение (конфиг отклоняется,
     * пул остаётся на последнем рабочем значении). Мягкие -> warning + починка.
     */
    public Normalized normalize() {
        List<String> warnings = new ArrayList<>();

        String url = require(jdbcUrl, "jdbcUrl");
        if (!url.startsWith("jdbc:")) {
            throw new InvalidSettingsException("jdbcUrl должен начинаться с 'jdbc:', получено: " + url);
        }
        String user = require(username, "username");
        if (password == null) {
            throw new InvalidSettingsException("password обязателен (пустая строка = вход без пароля)");
        }
        String name = require(poolName, "poolName");

        int max = intInRange(maximumPoolSize, "maximumPoolSize", 0, POOL_SIZE_MAX);
        int minIdle = maximumPoolSize == null
                ? POOL_SIZE_MIN
                : (minimumIdle == null ? maximumPoolSize : minimumIdle);
        minIdle = intInRange(minIdle, "minimumIdle", 0, POOL_SIZE_MAX);
        if (minIdle > max) {
            warnings.add("minimumIdle=" + minIdle + " > maximumPoolSize=" + max + " -> понижен до " + max);
            minIdle = max;
        }

        long connectionTimeout = longInRange(connectionTimeoutMs, "connectionTimeoutMs",
                CONNECTION_TIMEOUT_MIN_MS, CONNECTION_TIMEOUT_MAX_MS);
        long idleTimeout = longZeroOrRange(idleTimeoutMs, "idleTimeoutMs", IDLE_TIMEOUT_MIN_MS, IDLE_TIMEOUT_MAX_MS);
        long maxLifetime = longZeroOrRange(maxLifetimeMs, "maxLifetimeMs", MAX_LIFETIME_MIN_MS, MAX_LIFETIME_MAX_MS);
        long validationTimeout = longInRange(validationTimeoutMs, "validationTimeoutMs",
                VALIDATION_TIMEOUT_MIN_MS, VALIDATION_TIMEOUT_MAX_MS);
        long leakDetection = longZeroOrRange(leakDetectionThresholdMs, "leakDetectionThresholdMs",
                LEAK_DETECTION_MIN_MS, LEAK_DETECTION_MAX_MS);

        if (max > 0 && idleTimeout > 0 && minIdle == max) {
            // При максимуме 0 пула нет вовсе — предупреждение о сочетании минимума таймаута не нужно.
            warnings.add("idleTimeout не применим: minimumIdle == maximumPoolSize");
        }
        if (leakDetection > 0 && leakDetection >= connectionTimeout) {
            warnings.add("leakDetectionThresholdMs >= connectionTimeoutMs, возможны ложные срабатывания");
        }

        return new Normalized(new HikariSettings(url, user, password, name, max, minIdle,
                connectionTimeout, idleTimeout, maxLifetime, validationTimeout, leakDetection), warnings);
    }

    public record Normalized(HikariSettings settings, List<String> warnings) {}

    /** Список изменений вида "maximumPoolSize: 10 -> 20" для логов и признаков трассы. */
    public List<String> diff(HikariSettings other) {
        List<String> out = new ArrayList<>();
        addDiff(out, "maximumPoolSize", maximumPoolSize, other.maximumPoolSize);
        addDiff(out, "minimumIdle", minimumIdle, other.minimumIdle);
        addDiff(out, "connectionTimeoutMs", connectionTimeoutMs, other.connectionTimeoutMs);
        addDiff(out, "idleTimeoutMs", idleTimeoutMs, other.idleTimeoutMs);
        addDiff(out, "maxLifetimeMs", maxLifetimeMs, other.maxLifetimeMs);
        addDiff(out, "validationTimeoutMs", validationTimeoutMs, other.validationTimeoutMs);
        addDiff(out, "leakDetectionThresholdMs", leakDetectionThresholdMs, other.leakDetectionThresholdMs);
        addDiff(out, "jdbcUrl", jdbcUrl, other.jdbcUrl);
        addDiff(out, "username", username, other.username);
        addDiff(out, "poolName", poolName, other.poolName);
        if (!java.util.Objects.equals(password, other.password)) {
            out.add("password: " + mask(password) + " -> " + mask(other.password));
        }
        return out;
    }

    private static void addDiff(List<String> out, String field, Object from, Object to) {
        if (!java.util.Objects.equals(from, to)) {
            out.add(field + ": " + from + " -> " + to);
        }
    }

    /** Настройки без пароля — для журнала и признаков трассы. */
    public HikariSettings redacted() {
        String p = password;
        if (p != null && !p.isEmpty()) {
            p = "***";
        }
        return new HikariSettings(jdbcUrl, username, p, poolName, maximumPoolSize, minimumIdle,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, validationTimeoutMs, leakDetectionThresholdMs);
    }

    private static String mask(String password) {
        return (password == null || password.isEmpty()) ? "<empty>" : "***";
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        return value;
    }

    private static int intInRange(Integer value, String field, int min, int max) {
        if (value == null) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        if (value < min || value > max) {
            throw new InvalidSettingsException(field + "=" + value + " вне диапазона [" + min + ".." + max + "]");
        }
        return value;
    }

    private static long longInRange(Long value, String field, long min, long max) {
        if (value == null) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        if (value < min || value > max) {
            throw new InvalidSettingsException(field + "=" + value + " вне диапазона [" + min + ".." + max + "]");
        }
        return value;
    }

    private static long longZeroOrRange(Long value, String field, long min, long max) {
        if (value == null) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        if (value == 0L) {
            return 0L;
        }
        if (value < min || value > max) {
            throw new InvalidSettingsException(field + "=" + value + " вне диапазона [0 или " + min + ".." + max + "]");
        }
        return value;
    }
}
