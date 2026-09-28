package com.example.poolsvc.etcd;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.poolsvc.pool.HikariSettings;

/**
 * Разбор ключей etcd в настройки пула. Отдельный класс, чтобы это можно было
 * покрыть тестами без поднятия etcd.
 *
 * <p>Схема ключей (путь собирается из сегментов экземпляра, см. {@link EtcdKeyPath}):
 * <pre>
 *   /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/maximumPoolSize = 20
 *   /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/minimumIdle     = 5
 *   /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/connectionTimeoutMs = 3000
 * </pre>
 */
public final class EtcdKeys {

    public static final String MAX_POOL_SIZE = "maximumPoolSize";
    public static final String MIN_IDLE = "minimumIdle";
    public static final String CONNECTION_TIMEOUT = "connectionTimeoutMs";
    public static final String IDLE_TIMEOUT = "idleTimeoutMs";
    public static final String MAX_LIFETIME = "maxLifetimeMs";
    public static final String VALIDATION_TIMEOUT = "validationTimeoutMs";
    public static final String LEAK_DETECTION = "leakDetectionThresholdMs";
    public static final String JDBC_URL = "jdbcUrl";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String POOL_NAME = "poolName";

    public static final Set<String> ALL = Set.of(MAX_POOL_SIZE, MIN_IDLE, CONNECTION_TIMEOUT, IDLE_TIMEOUT,
            MAX_LIFETIME, VALIDATION_TIMEOUT, LEAK_DETECTION, JDBC_URL, USERNAME, PASSWORD, POOL_NAME);

    private EtcdKeys() {}

    /**
     * Разбирает ключи в настройки. Нечитаемые значения (мусор вместо числа) не роняют весь
     * конфиг: они попадают в {@link Parsed#problems()}, а в настройках остаются null, то есть
     * возьмутся локальные дефолты. Так одно битое значение не блокирует правку соседних ключей.
     */
    public static Parsed parse(Map<String, String> fullKeys, String prefix) {
        Map<String, String> problems = new HashMap<>();
        return new Parsed(new HikariSettings(
                value(fullKeys, prefix, JDBC_URL),
                value(fullKeys, prefix, USERNAME),
                value(fullKeys, prefix, PASSWORD),
                value(fullKeys, prefix, POOL_NAME),
                intValue(fullKeys, prefix, MAX_POOL_SIZE, problems),
                intValue(fullKeys, prefix, MIN_IDLE, problems),
                longValue(fullKeys, prefix, CONNECTION_TIMEOUT, problems),
                longValue(fullKeys, prefix, IDLE_TIMEOUT, problems),
                longValue(fullKeys, prefix, MAX_LIFETIME, problems),
                longValue(fullKeys, prefix, VALIDATION_TIMEOUT, problems),
                longValue(fullKeys, prefix, LEAK_DETECTION, problems)), problems);
    }

    /** Настройки + список ключей, которые не удалось прочитать (ключ -> причина). */
    public record Parsed(HikariSettings settings, Map<String, String> problems) {}

    /** Неизвестные ключи (опечатки) — возвращаем, чтобы залогировать один раз. */
    public static Set<String> unknownKeys(Map<String, String> fullKeys, String prefix) {
        Set<String> unknown = new HashSet<>();
        for (String key : fullKeys.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String shortKey = shortKey(key, prefix);
            if (!ALL.contains(shortKey)) {
                unknown.add(shortKey);
            }
        }
        return unknown;
    }

    public static String shortKey(String fullKey, String prefix) {
        return fullKey.startsWith(prefix) ? fullKey.substring(prefix.length()) : fullKey;
    }

    private static String value(Map<String, String> fullKeys, String prefix, String shortKey) {
        return fullKeys.get(prefix + shortKey);
    }

    private static Integer intValue(Map<String, String> fullKeys, String prefix, String shortKey,
            Map<String, String> problems) {
        String raw = value(fullKeys, prefix, shortKey);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            problems.put(shortKey, "'" + raw + "' — ожидалось целое число, взято значение по умолчанию");
            return null;
        }
    }

    private static Long longValue(Map<String, String> fullKeys, String prefix, String shortKey,
            Map<String, String> problems) {
        String raw = value(fullKeys, prefix, shortKey);
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            problems.put(shortKey, "'" + raw + "' — ожидалось целое число, взято значение по умолчанию");
            return null;
        }
    }
}
