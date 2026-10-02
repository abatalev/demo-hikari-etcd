package com.abatalev.demo.etcdhikari.provisor.etcd;

/**
 * Чистый разбор ключей дерева регистрации инстансов (провизор).
 *
 * <p>Грамматика (аналогична {@code EtcdKeyPath} у сервиса):
 * {@code {root}/services/{service}/groups/{group}/instances/{instance}/} — узел регистрации
 * инстанса; внутри него {@code .../instances/{instance}/hikari/{key}} — ключи конфигурации.
 * Узел хранится с пустым значением и хвостовым слешем, поэтому разбор принимает ключ узла как
 * с хвостовым слешем, так и без него.
 *
 * <p>На уровне сервиса живут три ключа бюджета соединений —
 * {@code {root}/services/{service}/activeMaxConnections},
 * {@code .../activeMinConnections} и {@code .../inactiveMaxConnections}. Это не узлы
 * регистрации: они не создают сервис и не увеличивают число живых инстансов, но по событию
 * изменения запускают пересчёт распределения бюджета. Тройка такая же, как в старых
 * {@code maxConnections}/{@code minConnections}: бюджета активного флота, минимальной доли
 * инстанса и резерва — размера пула неактивных групп (глобальный флот {@code {root}/groups/}).
 *
 * <p>Ключи {@code hikari/} провизор обязан игнорировать (их пишет он сам и оператор);
 * всё, что не узел, не ключ конфигурации и не сервисная настройка, — мусор/опечатка, его тоже
 * не трогаем.
 */
public final class InstanceKey {

    /** Распознанные сервисные настройки бюджета соединений. */
    public static final String ACTIVE_MAX_CONNECTIONS = "activeMaxConnections";

    public static final String ACTIVE_MIN_CONNECTIONS = "activeMinConnections";

    public static final String INACTIVE_MAX_CONNECTIONS = "inactiveMaxConnections";

    /**
     * Ключ публикации неосвобождённого сжатия под узлом регистрации.
     *
     * <p>Вне префикса {@code hikari/}: инстанс не считает его настройкой пула, и он не попадает ни в
     * снимок конфигурации, ни в watch инстанса.
     */
    public static final String UNRELEASED_CONNECTIONS = "unreleasedConnections";

    /** Результат разбора полного ключа. */
    public sealed interface Parsed permits Node, Config, Publication, ServiceSetting, Other {
    }

    /** Ключ — сам узел регистрации {@code .../instances/{instance}/}. */
    public record Node(String service, String group, String instance) implements Parsed {
    }

    /** Ключ — ключ конфигурации внутри {@code .../instances/{instance}/hikari/}. */
    public record Config(String service, String group, String instance) implements Parsed {
    }

    /**
     * Ключ — публикация неосвобождённого сжатия
     * {@code .../instances/{instance}/unreleasedConnections}.
     *
     * <p>Инстанс пишет сюда, сколько соединений он держит сверх своего потолка. Ключ лежит рядом
     * с узлом регистрации и вне префикса {@code hikari/}: это ответ инстанса, а не настройка пула,
     * поэтому в снимок конфигурации инстанса он не попадает и очисткой осиротевших префиксов не
     * затрагивается. Живёт на аренде узла — исчезает вместе с ним, отдельной уборки не требует.
     */
    public record Publication(String service, String group, String instance) implements Parsed {
    }

    /**
     * Ключ — сервисная настройка бюджета соединений {@code {root}/services/{service}/{setting}},
     * где setting — {@link #ACTIVE_MAX_CONNECTIONS}, {@link #ACTIVE_MIN_CONNECTIONS} или
     * {@link #INACTIVE_MAX_CONNECTIONS}.
     */
    public record ServiceSetting(String service, String setting) implements Parsed {
    }

    /** Ключ не относится к дереву инстансов (чужой корень, битые сегменты, не-hikari ключ). */
    public record Other() implements Parsed {
    }

    private static final String SERVICES = "services";
    private static final String GROUPS = "groups";
    private static final String INSTANCES = "instances";
    private static final String HIKARI = "hikari";

    private InstanceKey() {
    }

    /** Нормализация корня: обрезает хвостовые слеши (корень «/» остаётся «/»). */
    static String normalizedRoot(String root) {
        String r = root == null ? "" : root.trim();
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /**
     * Разбирает полный ключ относительно заданного корня.
     *
     * @param fullKey полный ключ, как его отдаёт etcd
     * @return {@link Node}, {@link Config}, {@link Publication}, {@link ServiceSetting}
     *     или {@link Other}
     */
    public static Parsed parse(String root, String fullKey) {
        String prefix = normalizedRoot(root) + "/" + SERVICES + "/";
        if (fullKey == null || !fullKey.startsWith(prefix)) {
            return new Other();
        }
        String rest = fullKey.substring(prefix.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String[] parts = rest.split("/");
        if (parts.length == 2 && !parts[0].isEmpty()) {
            return parseServiceSetting(parts[0], parts[1]);
        }
        if (parts.length < 5) {
            return new Other();
        }
        String service = parts[0];
        String group = parts[2];
        String instance = parts[4];
        if (service.isEmpty() || group.isEmpty() || instance.isEmpty()) {
            return new Other();
        }
        // Сегменты не должны содержать '/' — split уже гарантировал отсутствие.
        // Строгий порядок сегментов обязателен: это же дерево читают сервисы.
        if (!GROUPS.equals(parts[1]) || !INSTANCES.equals(parts[3])) {
            return new Other();
        }
        if (parts.length == 5) {
            return new Node(service, group, instance);
        }
        if (HIKARI.equals(parts[5])) {
            return new Config(service, group, instance);
        }
        if (parts.length == 6 && UNRELEASED_CONNECTIONS.equals(parts[5])) {
            return new Publication(service, group, instance);
        }
        return new Other();
    }

    /** Распознанная сервисная настройка бюджета или {@link Other} (мусор/опечатка). */
    private static Parsed parseServiceSetting(String service, String setting) {
        if (ACTIVE_MAX_CONNECTIONS.equals(setting) || ACTIVE_MIN_CONNECTIONS.equals(setting)
                || INACTIVE_MAX_CONNECTIONS.equals(setting)) {
            return new ServiceSetting(service, setting);
        }
        return new Other();
    }

    /** Полный ключ сервисной настройки бюджета {@code {root}/services/{service}/{setting}}. */
    public static String serviceSettingKey(String root, String service, String setting) {
        return normalizedRoot(root) + "/" + SERVICES + "/" + service + "/" + setting;
    }

    /** Полный ключ узла регистрации инстанса (с хвостовым слешем). */
    public static String nodeKey(String root, String service, String group, String instance) {
        return normalizedRoot(root) + "/" + SERVICES + "/" + service + "/" + GROUPS + "/" + group
                + "/" + INSTANCES + "/" + instance + "/";
    }

    /** Префикс ключей конфигурации инстанса {@code .../instances/{instance}/hikari/}. */
    public static String hikariPrefix(String root, String service, String group, String instance) {
        return nodeKey(root, service, group, instance) + HIKARI + "/";
    }

    /** Полный ключ публикации неосвобождённого сжатия {@code .../instances/{i}/unreleasedConnections}. */
    public static String unreleasedConnectionsKey(String root, String service, String group,
            String instance) {
        return nodeKey(root, service, group, instance) + UNRELEASED_CONNECTIONS;
    }

    /**
     * Множество живых сервисов по ключам снимка {@code {root}/services/}: сегмент {@code {service}}
     * каждого узла регистрации. Ключи конфигурации, сервисные настройки бюджета, обрывки путей и
     * любой мусор (включая ключи выборов лидера — они лежат вне {@code {root}/services/})
     * сервисами не считаются: сервис без живых узлов лидера не имеет.
     */
    public static java.util.Set<String> liveServices(String root, java.util.List<String> keys) {
        java.util.Set<String> services = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            Parsed parsed = parse(root, key);
            if (parsed instanceof Node n) {
                services.add(n.service());
            }
        }
        return services;
    }
}
