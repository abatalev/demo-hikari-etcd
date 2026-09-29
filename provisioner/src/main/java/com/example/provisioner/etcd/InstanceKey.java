package com.example.provisioner.etcd;

/**
 * Чистый разбор ключей дерева регистрации инстансов (провизор).
 *
 * <p>Грамматика (аналогична {@code EtcdKeyPath} у сервиса):
 * {@code {root}/services/{service}/groups/{group}/instances/{instance}/} — узел регистрации
 * инстанса; внутри него {@code .../instances/{instance}/hikari/{key}} — ключи конфигурации.
 * Узел хранится с пустым значением и хвостовым слешом, поэтому разбор принимает ключ узла как
 * с хвостовым слешом, так и без него.
 *
 * <p>Ключи {@code hikari/} провизор обязан игнорировать (их пишет он сам и оператор);
 * всё, что не узел и не ключ конфигурации, — мусор/опечатка, его тоже не трогаем.
 */
public final class InstanceKey {

    /** Результат разбора полного ключа. */
    public sealed interface Parsed permits Node, Config, Other {
    }

    /** Ключ — сам узел регистрации {@code .../instances/{instance}/}. */
    public record Node(String service, String group, String instance) implements Parsed {
    }

    /** Ключ — ключ конфигурации внутри {@code .../instances/{instance}/hikari/}. */
    public record Config(String service, String group, String instance) implements Parsed {
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
     * @return {@link Node}, {@link Config} или {@link Other}
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
        return new Other();
    }

    /** Полный ключ узла регистрации инстанса (с хвостовым слешом). */
    public static String nodeKey(String root, String service, String group, String instance) {
        return normalizedRoot(root) + "/" + SERVICES + "/" + service + "/" + GROUPS + "/" + group
                + "/" + INSTANCES + "/" + instance + "/";
    }

    /** Префикс ключей конфигурации инстанса {@code .../instances/{instance}/hikari/}. */
    public static String hikariPrefix(String root, String service, String group, String instance) {
        return nodeKey(root, service, group, instance) + HIKARI + "/";
    }
}