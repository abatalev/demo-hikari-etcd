package com.example.poolsvc.etcd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EtcdKeysTest {

    private static final String PREFIX = "/config/pool-service/hikari/";

    @Test
    void readsFlatKeysIntoSettings() {
        Map<String, String> keys = Map.of(
                PREFIX + "maximumPoolSize", "24",
                PREFIX + "minimumIdle", "6",
                PREFIX + "connectionTimeoutMs", "2500");

        EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, PREFIX);

        assertThat(parsed.settings().maximumPoolSize()).isEqualTo(24);
        assertThat(parsed.settings().minimumIdle()).isEqualTo(6);
        assertThat(parsed.settings().connectionTimeoutMs()).isEqualTo(2500L);
        // не задано в etcd -> null, дальше разрулит resolve(defaults)
        assertThat(parsed.settings().maxLifetimeMs()).isNull();
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    void missingKeysGiveNulls() {
        assertThat(EtcdKeys.parse(Map.of(), PREFIX).settings().maximumPoolSize()).isNull();
    }

    @Test
    void garbageValueIsIgnoredButReportedAndDoesNotBlockOtherKeys() {
        Map<String, String> keys = Map.of(
                PREFIX + "maximumPoolSize", "много",
                PREFIX + "minimumIdle", "4");

        EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, PREFIX);

        // битое значение -> null (возьмётся дефолт), остальные ключи читаются как обычно
        assertThat(parsed.settings().maximumPoolSize()).isNull();
        assertThat(parsed.settings().minimumIdle()).isEqualTo(4);
        assertThat(parsed.problems()).containsOnlyKeys(EtcdKeys.MAX_POOL_SIZE);
        assertThat(parsed.problems().get(EtcdKeys.MAX_POOL_SIZE)).contains("много");
    }

    @Test
    void valuesAreTrimmed() {
        Map<String, String> keys = Map.of(PREFIX + "maximumPoolSize", " 8\n");

        assertThat(EtcdKeys.parse(keys, PREFIX).settings().maximumPoolSize()).isEqualTo(8);
    }

    @Test
    void typosInKeysAreReported() {
        Map<String, String> keys = Map.of(
                PREFIX + "maxPoolSize", "10",
                PREFIX + "maximumPoolSize", "10",
                "/other/prefix", "x");

        assertThat(EtcdKeys.unknownKeys(keys, PREFIX)).containsExactly("maxPoolSize");
    }
}
