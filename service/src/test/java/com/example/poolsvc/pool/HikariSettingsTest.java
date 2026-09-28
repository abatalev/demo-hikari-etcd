package com.example.poolsvc.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HikariSettingsTest {

    private static HikariSettings base() {
        return new HikariSettings("jdbc:postgresql://localhost:5432/demo", "app", "app", "pool-service",
                10, null, 30_000L, 600_000L, 1_800_000L, 5_000L, 0L);
    }

    @Test
    void minimumIdleFollowsMaximumPoolSizeWhenNotSet() {
        HikariSettings.Normalized normalized = base().resolve(base()).normalize();

        assertThat(normalized.settings().minimumIdle()).isEqualTo(10);
        // предупреждение ожидаемо: при minimumIdle == maximumPoolSize idleTimeout не применяется
        assertThat(normalized.warnings()).containsExactly("idleTimeout не применим: minimumIdle == maximumPoolSize");
    }

    @Test
    void etcdOverridesLocalDefaults() {
        HikariSettings fromEtcd = new HikariSettings(null, null, null, null, 42, null,
                null, null, null, null, null);
        HikariSettings merged = fromEtcd.resolve(base());

        assertThat(merged.maximumPoolSize()).isEqualTo(42);
        assertThat(merged.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/demo");
        assertThat(merged.normalize().settings().minimumIdle()).isEqualTo(42);
    }

    @Test
    void minimumIdleGreaterThanMaxIsClampedWithWarning() {
        HikariSettings settings = new HikariSettings("jdbc:postgresql://x/y", "app", "app", "pool",
                5, 20, 30_000L, 600_000L, 1_800_000L, 5_000L, 0L);

        HikariSettings.Normalized normalized = settings.normalize();

        assertThat(normalized.settings().minimumIdle()).isEqualTo(5);
        assertThat(normalized.warnings()).anyMatch(w -> w.contains("minimumIdle"));
    }

    @Test
    void poolSizeOutOfRangeIsRejected() {
        HikariSettings settings = new HikariSettings("jdbc:postgresql://x/y", "app", "app", "pool",
                0, 0, 30_000L, 600_000L, 1_800_000L, 5_000L, 0L);

        assertThatThrownBy(settings::normalize)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("maximumPoolSize");
    }

    @Test
    void tooSmallConnectionTimeoutIsRejected() {
        HikariSettings settings = new HikariSettings("jdbc:postgresql://x/y", "app", "app", "pool",
                10, 10, 100L, 600_000L, 1_800_000L, 5_000L, 0L);

        assertThatThrownBy(settings::normalize)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("connectionTimeoutMs");
    }

    @Test
    void diffDescribesChangesAndHidesPassword() {
        HikariSettings next = new HikariSettings("jdbc:postgresql://localhost:5432/demo", "app", "changed",
                "pool-service", 25, null, 30_000L, 600_000L, 1_800_000L, 5_000L, 0L);

        List<String> diff = base().diff(next);

        assertThat(diff).contains("maximumPoolSize: 10 -> 25");
        assertThat(diff).anyMatch(d -> d.startsWith("password: *** -> ***"));
    }

    @Test
    void sameTargetDetectsRecreateRequired() {
        HikariSettings other = new HikariSettings("jdbc:postgresql://other:5432/demo", "app", "app",
                "pool-service", 10, null, 30_000L, 600_000L, 1_800_000L, 5_000L, 0L);

        assertThat(base().sameTarget(other)).isFalse();
        assertThat(base().sameTarget(base())).isTrue();
    }
}
