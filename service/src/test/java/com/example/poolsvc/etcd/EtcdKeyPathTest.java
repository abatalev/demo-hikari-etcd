package com.example.poolsvc.etcd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EtcdKeyPathTest {

    @Test
    void buildsPathWithIdentitySegments() {
        String path = EtcdKeyPath.build("/config", "service-a", "group-1", "service-a-group-1-1");

        assertThat(path).isEqualTo("/config/services/service-a/groups/group-1/instances/service-a-group-1-1/hikari/");
    }

    @Test
    void trimsTrailingSlashOfRoot() {
        assertThat(EtcdKeyPath.build("/config/", "s", "g", "i"))
                .isEqualTo("/config/services/s/groups/g/instances/i/hikari/");
    }

    @Test
    void rootMayGetLeadingSlashAsGiven() {
        assertThat(EtcdKeyPath.build("config", "s", "g", "i"))
                .isEqualTo("config/services/s/groups/g/instances/i/hikari/");
    }

    @Test
    void blankGroupIsRejectedWithItsName() {
        assertThatThrownBy(() -> EtcdKeyPath.build("/config", "service-a", "  ", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    @Test
    void blankInstanceIsRejectedWithItsName() {
        assertThatThrownBy(() -> EtcdKeyPath.build("/config", "service-a", "g", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance");
    }

    @Test
    void blankRootIsRejected() {
        assertThatThrownBy(() -> EtcdKeyPath.build("", "service-a", "g", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root");
    }

    @Test
    void instanceWithSeparatorIsRejected() {
        assertThatThrownBy(() -> EtcdKeyPath.build("/config", "service-a", "g", "i/j"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance");
    }

    @Test
    void serviceWithSeparatorIsRejected() {
        assertThatThrownBy(() -> EtcdKeyPath.build("/config", "ser/vice", "g", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
    }

    @Test
    void nodePathIsConfigPathWithoutHikari() {
        assertThat(EtcdKeyPath.nodePath("/config", "service-a", "group-1", "service-a-group-1-1"))
                .isEqualTo("/config/services/service-a/groups/group-1/instances/service-a-group-1-1/");
    }

    @Test
    void nodePathAndBuildShareTheSamePrefix() {
        String config = EtcdKeyPath.build("/config", "s", "g", "i");
        assertThat(config).startsWith(EtcdKeyPath.nodePath("/config", "s", "g", "i"));
        assertThat(config).isEqualTo(EtcdKeyPath.nodePath("/config", "s", "g", "i") + "hikari/");
    }

    @Test
    void nodePathValidatesSegmentsLikeBuild() {
        assertThatThrownBy(() -> EtcdKeyPath.nodePath("/config", "service-a", "g", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance");
    }

    @Test
    void unreleasedConnectionsKeyLivesBesideNodeOutsideHikari() {
        // Публикация — ответ инстанса, а не настройка пула: внутри hikari/ ей не место, оттуда её
        // забрала бы уборка префикса при снятии доли.
        String publication = EtcdKeyPath.unreleasedConnectionsPath("/config", "service-a", "group-1",
                "service-a-group-1-1");

        assertThat(publication).isEqualTo(
                "/config/services/service-a/groups/group-1/instances/service-a-group-1-1/unreleasedConnections");
        assertThat(publication).startsWith(EtcdKeyPath.nodePath("/config", "service-a", "group-1",
                "service-a-group-1-1"));
        assertThat(publication).doesNotContain("hikari/");
    }

    @Test
    void unreleasedConnectionsKeyTrimsTrailingSlashOfRoot() {
        assertThat(EtcdKeyPath.unreleasedConnectionsPath("/config/", "s", "g", "i"))
                .isEqualTo("/config/services/s/groups/g/instances/i/unreleasedConnections");
    }

    @Test
    void unreleasedConnectionsKeyValidatesSegments() {
        assertThatThrownBy(
                () -> EtcdKeyPath.unreleasedConnectionsPath("/config", "service-a", "  ", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    @Test
    void unreleasedConnectionsIsNotAConfigKey() {
        // Путь публикации не должен собираться грамматикой конфигурации: иначе сервис принял бы
        // ответ провизёру за настройку пула.
        assertThat(EtcdKeyPath.build("/config", "s", "g", "i"))
                .doesNotContain(EtcdKeyPath.UNRELEASED_CONNECTIONS);
    }
}