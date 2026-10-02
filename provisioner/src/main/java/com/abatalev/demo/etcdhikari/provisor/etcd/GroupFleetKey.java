package com.abatalev.demo.etcdhikari.provisor.etcd;

/**
 * Чистый разбор маркеров активности глобального флота групп.
 *
 * <p>Маркер живёт на один уровень выше дерева сервисов — {@code {root}/groups/{group}/active}
 * со значением {@code true|false}. Группа — <em>глобальный флот</em>: имя совпадает у разных
 * сервисов (например, {@code group-1} у service-a и service-b — это одна и та же группа), и
 * неактивность группы сжимает пулы всех её инстансов во всех сервисах. У каждого сервиса при этом
 * свои ключи бюджета ({@code activeMaxConnections}/{@code activeMinConnections}/
 * {@code inactiveMaxConnections}).
 *
 * <p>Семантика значения: группа активна, пока маркер отсутствует (ещё не помечена) или значение
 * не равно {@code false}; неактивна — только при явном {@code false}. Мусор в значении
 * провижер WARN'ит один раз и трактует группу как активную (fail-open: без явного выключения
 * обслуживаем).
 */
public final class GroupFleetKey {

    /** Результат разбора полного ключа. */
    public sealed interface Parsed permits Marker, Other {
    }

    /** Ключ — маркер активности группы {@code {root}/groups/{group}/active}. */
    public record Marker(String group) implements Parsed {
    }

    /** Ключ не относится к маркерам групп (чужой корень, битые сегменты, не-active ключ). */
    public record Other() implements Parsed {
    }

    private static final String GROUPS = "groups";
    private static final String ACTIVE = "active";

    private GroupFleetKey() {
    }

    /** Префикс маркеров групп {@code {root}/groups/}. */
    public static String groupsPrefix(String root) {
        return InstanceKey.normalizedRoot(root) + "/" + GROUPS + "/";
    }

    /** Полный ключ маркера активности группы {@code {root}/groups/{group}/active}. */
    public static String markerKey(String root, String group) {
        return groupsPrefix(root) + group + "/" + ACTIVE;
    }

    /**
     * Разбирает полный ключ маркера.
     *
     * @param fullKey полный ключ, как его отдаёт etcd
     * @return {@link Marker} или {@link Other} (мусор/опечатка)
     */
    public static Parsed parse(String root, String fullKey) {
        String prefix = groupsPrefix(root);
        if (fullKey == null || !fullKey.startsWith(prefix)) {
            return new Other();
        }
        String rest = fullKey.substring(prefix.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String[] parts = rest.split("/");
        if (parts.length == 2 && !parts[0].isEmpty() && ACTIVE.equals(parts[1])) {
            return new Marker(parts[0]);
        }
        return new Other();
    }

    /**
     * Семантика значения маркера: группа активна, кроме явного {@code false} (без учёта регистра).
     * Отсутствие маркера ({@code null}) и любой мусор — активна (fail-open).
     */
    public static boolean isActive(String rawValue) {
        return rawValue == null || !"false".equalsIgnoreCase(rawValue.trim());
    }
}