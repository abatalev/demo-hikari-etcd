package com.example.poolsvc.etcd;

/**
 * Адресация ключей конфигурации: путь
 * <pre>
 *   {root}/services/{service}/groups/{group}/instances/{instance}/hikari/
 * </pre>
 *
 * <p>Каждый сущностный сегмент помечен статическим маркером: {@code services}, {@code groups},
 * {@code instances}. Маркеры одинаковы для всех инстансов и делают роль сегмента явной для
 * читателя пути (в стиле k8s). Сегменты задают, чья это конфигурация: имя сервиса, группа
 * (namespace), имя инстанса (пода). Хвостовой сегмент {@code hikari} фиксирован и отделяет
 * настройки пула от остальных ключей экземпляра.
 *
 * <p>Чистая функция: путь собирается и проверяется здесь, чтобы адресацию можно было покрыть
 * тестами без etcd. Сегменты проверяются на пустоту и на отсутствие разделителя пути — иначе
 * конфигурация одного инстанса молча уехала бы в чужой путь.
 */
public final class EtcdKeyPath {

    /** Сегмент с ключами настроек пула; одинаков для всех инстансов. */
    public static final String HIKARI = "hikari";

    /** Маркер сегмента сервиса; фиксирован и одинаков для всех инстансов. */
    public static final String SERVICES = "services";

    /** Маркер сегмента группы; фиксирован и одинаков для всех инстансов. */
    public static final String GROUPS = "groups";

    /** Маркер сегмента инстанса; фиксирован и одинаков для всех инстансов. */
    public static final String INSTANCES = "instances";

    private EtcdKeyPath() {}

    /**
     * Собирает путь из сегментов. Каждый сегмент обязан быть непустым и не содержать «/».
     * Корень может содержать «/» (например, {@code /config}); хвостовой слеш корня срезается.
     *
     * @throws IllegalArgumentException название проблемного сегмента в сообщении
     */
    public static String build(String root, String service, String group, String instance) {
        return nodePath(root, service, group, instance) + HIKARI + "/";
    }

    /**
     * Путь узла регистрации инстанса: путь конфигурации без хвостового сегмента {@code hikari}.
     *
     * <p>Узел появляется в etcd, когда инстанс регистрируется (аренда), и исчезает при его
     * остановке. Конфиг-воркер читает только префикс {@code .../hikari/}, поэтому узел не попадает
     * ни в снимок, ни в события watch — он невидим для применения конфигурации и для гейта.
     */
    public static String nodePath(String root, String service, String group, String instance) {
        requireNonBlank(root, "root");
        requireSegment(service, "service");
        requireSegment(group, "group");
        requireSegment(instance, "instance");
        String trimmedRoot = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        return trimmedRoot + "/" + SERVICES + "/" + service + "/" + GROUPS + "/" + group
                + "/" + INSTANCES + "/" + instance + "/";
    }

    private static void requireSegment(String segment, String name) {
        requireNonBlank(segment, name);
        if (segment.contains("/")) {
            throw new IllegalArgumentException(
                    "сегмент пути конфигурации '" + name + "' содержит '/' (" + segment + ")");
        }
    }

    private static void requireNonBlank(String segment, String name) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("сегмент пути конфигурации '" + name + "' не заполнен");
        }
    }
}