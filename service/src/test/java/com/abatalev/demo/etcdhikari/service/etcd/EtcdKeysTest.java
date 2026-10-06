package com.abatalev.demo.etcdhikari.service.etcd;

import static org.assertj.core.api.Assertions.assertThat;

import com.abatalev.demo.etcdhikari.service.pool.PoolSize;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EtcdKeysTest {

    private static final String PREFIX = "/config/pool-service/hikari/";

    @Test
    @DisplayName("из etcd читаются только размер и минимум")
    void readsOnlySizeKeysIntoSize() {
        Map<String, String> keys = Map.of(
                PREFIX + "maximumPoolSize", "24",
                PREFIX + "minimumIdle", "6");

        EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, PREFIX);

        assertThat(parsed.size().maximumPoolSize()).isEqualTo(24);
        assertThat(parsed.size().minimumIdle()).isEqualTo(6);
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    @DisplayName("перечень распознаваемых ключей состоит из двух имён")
    void recognizedKeysAreOnlySizeKeys() {
        assertThat(EtcdKeys.ALL).containsExactlyInAnyOrder("maximumPoolSize", "minimumIdle");
    }

    @Test
    void missingKeysGiveNulls() {
        assertThat(EtcdKeys.parse(Map.of(), PREFIX).size().maximumPoolSize()).isNull();
    }

    @Test
    void garbageValueIsIgnoredButReportedAndDoesNotBlockOtherKeys() {
        Map<String, String> keys = Map.of(
                PREFIX + "maximumPoolSize", "много",
                PREFIX + "minimumIdle", "4");

        EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, PREFIX);

        // битое значение -> null (пул закроется), остальные ключи читаются как обычно
        assertThat(parsed.size().maximumPoolSize()).isNull();
        assertThat(parsed.size().minimumIdle()).isEqualTo(4);
        assertThat(parsed.problems()).containsOnlyKeys(EtcdKeys.MAX_POOL_SIZE);
        assertThat(parsed.problems().get(EtcdKeys.MAX_POOL_SIZE)).contains("много");
    }

    @Test
    void valuesAreTrimmed() {
        Map<String, String> keys = Map.of(PREFIX + "maximumPoolSize", " 8\n");

        assertThat(EtcdKeys.parse(keys, PREFIX).size().maximumPoolSize()).isEqualTo(8);
    }

    @Test
    void typosInKeysAreReported() {
        Map<String, String> keys = Map.of(
                PREFIX + "maxPoolSize", "10",
                PREFIX + "maximumPoolSize", "10",
                "/other/prefix", "x");

        assertThat(EtcdKeys.unknownKeys(keys, PREFIX)).containsExactly("maxPoolSize");
    }

    @Test
    @DisplayName("цель соединения и таймауты считаются неизвестными, значение не применяется")
    void targetAndTimeoutKeysAreUnknown() {
        Map<String, String> keys = Map.of(
                PREFIX + "maximumPoolSize", "24",
                PREFIX + "jdbcUrl", "jdbc:postgresql://другая/demo",
                PREFIX + "username", "root",
                PREFIX + "password", "secret",
                PREFIX + "poolName", "чужой-пул",
                PREFIX + "connectionTimeoutMs", "1000");

        EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, PREFIX);

        // ключи вне перечня не применяются: пул остаётся на локально заданной цели
        assertThat(parsed.size()).isEqualTo(new PoolSize(24, null));
        assertThat(EtcdKeys.unknownKeys(keys, PREFIX))
                .containsExactlyInAnyOrder("jdbcUrl", "username", "password", "poolName",
                        "connectionTimeoutMs");
    }
}