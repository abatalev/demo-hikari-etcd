package com.example.provisioner.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/**
 * Чистая грамматика пути — единственное, что покрывается юнит-тестами у провизора
 * (watch-цикл и сверка проверяются на живом стенде, Testcontainers в проекте нет).
 */
class InstanceKeyTest {

    private static final String ROOT = "/config";

    @Test
    void parsesNodeKey() {
        var parsed = InstanceKey.parse(ROOT,
                "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/");
        assertInstanceOf(InstanceKey.Node.class, parsed);
        assertEquals(new InstanceKey.Node("service-a", "group-1", "service-a-group-1-1"), parsed);
    }

    @Test
    void parsesNodeKeyWithoutTrailingSlash() {
        var parsed = InstanceKey.parse(ROOT,
                "/config/services/service-a/groups/group-1/instances/service-a-group-1-1");
        assertInstanceOf(InstanceKey.Node.class, parsed);
        assertEquals(new InstanceKey.Node("service-a", "group-1", "service-a-group-1-1"), parsed);
    }

    @Test
    void parsesConfigKey() {
        var parsed = InstanceKey.parse(ROOT, "/config/services/service-a/groups/group-1/instances/"
                + "service-a-group-1-1/hikari/maximumPoolSize");
        assertInstanceOf(InstanceKey.Config.class, parsed);
        assertEquals(new InstanceKey.Config("service-a", "group-1", "service-a-group-1-1"), parsed);
    }

    @Test
    void parsesDeepConfigKeyAsConfig() {
        var parsed = InstanceKey.parse(ROOT, "/config/services/a/groups/g/instances/i/hikari/minimumIdle");
        assertInstanceOf(InstanceKey.Config.class, parsed);
    }

    @Test
    void parsesHikariPrefixAsConfig() {
        // Одинокий префикс (без ключа) тоже не узел — это ключ конфигурации (пустой).
        var parsed = InstanceKey.parse(ROOT, "/config/services/a/groups/g/instances/i/hikari/");
        assertInstanceOf(InstanceKey.Config.class, parsed);
    }

    @Test
    void rejectsForeignRoot() {
        assertInstanceOf(InstanceKey.Other.class,
                InstanceKey.parse("/other/root", "/config/services/a/groups/g/instances/i/"));
    }

    @Test
    void rejectsTooShortKey() {
        assertInstanceOf(InstanceKey.Other.class, InstanceKey.parse(ROOT, "/config/services/"));
        assertInstanceOf(InstanceKey.Other.class, InstanceKey.parse(ROOT, "/config/services/a/"));
        assertInstanceOf(InstanceKey.Other.class,
                InstanceKey.parse(ROOT, "/config/services/a/groups/g/instances/"));
    }

    @Test
    void rejectsWrongSegmentOrder() {
        assertInstanceOf(InstanceKey.Other.class, InstanceKey.parse(ROOT,
                "/config/services/service-a/instances/service-a-group-1-1/groups/group-1/"));
    }

    @Test
    void rejectsEmptySegment() {
        assertInstanceOf(InstanceKey.Other.class,
                InstanceKey.parse(ROOT, "/config/services//groups/g/instances/i/"));
    }

    @Test
    void rejectsNonHikariInstanceSubkey() {
        assertInstanceOf(InstanceKey.Other.class,
                InstanceKey.parse(ROOT, "/config/services/a/groups/g/instances/i/foo/bar"));
    }

    @Test
    void rejectsNullKey() {
        assertInstanceOf(InstanceKey.Other.class, InstanceKey.parse(ROOT, null));
    }

    @Test
    void nodeKeyRoundTrip() {
        String key = InstanceKey.nodeKey(ROOT, "service-a", "group-1", "instance-1");
        assertEquals("/config/services/service-a/groups/group-1/instances/instance-1/", key);
        assertInstanceOf(InstanceKey.Node.class, InstanceKey.parse(ROOT, key));
    }

    @Test
    void hikariPrefixRoundTrip() {
        String prefix = InstanceKey.hikariPrefix(ROOT, "service-a", "group-1", "instance-1");
        assertEquals(
                "/config/services/service-a/groups/group-1/instances/instance-1/hikari/", prefix);
        assertInstanceOf(InstanceKey.Config.class, InstanceKey.parse(ROOT, prefix + "maximumPoolSize"));
    }

    @Test
    void rootWithTrailingSlashIsNormalized() {
        String key = InstanceKey.nodeKey("/config/", "a", "g", "i");
        assertEquals("/config/services/a/groups/g/instances/i/", key);
        assertInstanceOf(InstanceKey.Node.class, InstanceKey.parse("/config/", key));
    }

    @Test
    void rootWithoutLeadingSlashWorks() {
        // Нестандартный корень без ведущего слеша — тоже допустимая грамматика.
        String key = InstanceKey.nodeKey("config", "a", "g", "i");
        assertEquals("config/services/a/groups/g/instances/i/", key);
        assertInstanceOf(InstanceKey.Node.class, InstanceKey.parse("config", key));
    }

    @Test
    void liveServicesCollectOnlyNodeKeys() {
        var keys = java.util.List.of(
                "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/",
                "/config/services/service-a/groups/group-2/instances/service-a-group-2-1/",
                "/config/services/service-b/groups/group-1/instances/service-b-group-1-1/",
                "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari"
                        + "/maximumPoolSize",
                "/config/services/service-b/groups/g/instances/i/foo/bar",
                "/config/provisioner/leader/service-a/deadbeef");
        assertEquals(java.util.Set.of("service-a", "service-b"),
                InstanceKey.liveServices(ROOT, keys));
    }

    @Test
    void liveServicesIgnoreEmptyAndForeignKeys() {
        assertEquals(java.util.Set.of(), InstanceKey.liveServices(ROOT, java.util.List.of()));
        assertEquals(java.util.Set.of(), InstanceKey.liveServices(ROOT,
                java.util.List.of("/other/root/services/a/groups/g/instances/i/",
                        "/config/services/", "/config/services/a/")));
    }

    @Test
    void liveServicesDeduplicateByServiceSegment() {
        var keys = java.util.List.of(
                "/config/services/a/groups/g1/instances/i1/",
                "/config/services/a/groups/g2/instances/i2/",
                "/config/services/a/groups/g2/instances/i2/hikari/connectionTimeoutMs");
        assertEquals(java.util.Set.of("a"), InstanceKey.liveServices(ROOT, keys));
    }

    @Test
    void parsesServiceBudgetKeys() {
        var max = InstanceKey.parse(ROOT, "/config/services/service-a/maxConnections");
        assertInstanceOf(InstanceKey.ServiceSetting.class, max);
        assertEquals(new InstanceKey.ServiceSetting("service-a", "maxConnections"), max);

        var min = InstanceKey.parse(ROOT, "/config/services/service-a/minConnections");
        assertInstanceOf(InstanceKey.ServiceSetting.class, min);
        assertEquals(new InstanceKey.ServiceSetting("service-a", "minConnections"), min);
    }

    @Test
    void rejectsUnknownServiceLevelKey() {
        // Сервисный уровень: распознаются только два ключа бюджета, остальное — опечатка.
        assertInstanceOf(InstanceKey.Other.class, InstanceKey.parse(ROOT, "/config/services/a/foo"));
        assertInstanceOf(InstanceKey.Other.class, InstanceKey.parse(ROOT, "/config/services/a"));
        assertInstanceOf(InstanceKey.Other.class,
                InstanceKey.parse(ROOT, "/config/services/a/maxConnections/extra"));
        assertInstanceOf(InstanceKey.Other.class,
                InstanceKey.parse(ROOT, "/config/services//maxConnections"));
    }

    @Test
    void serviceSettingKeyRoundTrip() {
        String key = InstanceKey.serviceSettingKey(ROOT, "service-a", InstanceKey.MAX_CONNECTIONS);
        assertEquals("/config/services/service-a/maxConnections", key);
        assertInstanceOf(InstanceKey.ServiceSetting.class, InstanceKey.parse(ROOT, key));
    }

    @Test
    void liveServicesIgnoresServiceBudgetKeys() {
        // Ключи бюджета не создают сервис и не увеличивают число живых инстансов.
        var keys = java.util.List.of(
                "/config/services/service-a/maxConnections",
                "/config/services/service-a/minConnections",
                "/config/services/service-b/maxConnections");
        assertEquals(java.util.Set.of(), InstanceKey.liveServices(ROOT, keys));
    }
}