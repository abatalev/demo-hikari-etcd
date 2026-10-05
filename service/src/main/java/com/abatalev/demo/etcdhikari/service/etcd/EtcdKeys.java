package com.abatalev.demo.etcdhikari.service.etcd;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.abatalev.demo.etcdhikari.service.pool.HikariSettings;

/**
 * Разбор ключей etcd в настройки пула. Отдельный класс, чтобы это можно было
 * покрыть тестами без поднятия etcd.
 *
 * <p>Хранилище конфигурации владеет только размером пула. Схема ключей (путь собирается из
 * сегментов экземпляра, см. {@link EtcdKeyPath}):
 * <pre>
 *   /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/maximumPoolSize = 20
 *   /config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/minimumIdle     = 5
 * </pre>
 *
 * <p>Цель соединения (адрес базы, учётные данные, имя пула) и таймауты сюда не входят: они
 * приходят только из локальной конфигурации процесса, поэтому менять их на живом пуле нечем.
 * Ключ вне перечня — опечатка, и он попадает в {@link #unknownKeys(Map, String)}.
 */
public final class EtcdKeys {

    public static final String MAX_POOL_SIZE = "maximumPoolSize";
    public static final String MIN_IDLE = "minimumIdle";

    public static final Set<String> ALL = Set.of(MAX_POOL_SIZE, MIN_IDLE);

    private EtcdKeys() {}

    /**
     * Разбирает ключи в настройки. Нечитаемые значения (мусор вместо числа) не роняют весь
     * конфиг: они попадают в {@link Parsed#problems()}, а в настройках остаются null, то есть
     * возьмутся локальные дефолты. Так одно битое значение не блокирует правку соседних ключей.
     */
    public static Parsed parse(Map<String, String> fullKeys, String prefix) {
        Map<String, String> problems = new HashMap<>();
        // null в целевых и таймаутных полях: из etcd они не приходят, их подставит resolve(defaults)
        // из локальной конфигурации процесса.
        return new Parsed(new HikariSettings(
                null,
                null,
                null,
                null,
                intValue(fullKeys, prefix, MAX_POOL_SIZE, problems),
                intValue(fullKeys, prefix, MIN_IDLE, problems),
                null,
                null,
                null,
                null,
                null), problems);
    }

    /** Настройки + список ключей, которые не удалось прочитать (ключ -> причина). */
    public static record Parsed(HikariSettings settings, Map<String, String> problems) {}

    /** Неизвестные ключи (опечатки и снятые настройки) — возвращаем, чтобы залогировать один раз. */
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

    private static String value(Map<String, String> fullKeys, String prefix, String shortKey) {
        return fullKeys.get(prefix + shortKey);
    }
}