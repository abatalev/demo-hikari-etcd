package com.example.poolsvc.etcd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EtcdKeyPathTest {

    @Test
    void buildsPathWithIdentitySegments() {
        String path = EtcdKeyPath.build("/config", "service-a", "group-1", "service-a-group-1-1");

        assertThat(path).isEqualTo("/config/service-a/group-1/service-a-group-1-1/hikari/");
    }

    @Test
    void trimsTrailingSlashOfRoot() {
        assertThat(EtcdKeyPath.build("/config/", "s", "g", "i")).isEqualTo("/config/s/g/i/hikari/");
    }

    @Test
    void rootMayGetLeadingSlashAsGiven() {
        assertThat(EtcdKeyPath.build("config", "s", "g", "i")).isEqualTo("config/s/g/i/hikari/");
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
}