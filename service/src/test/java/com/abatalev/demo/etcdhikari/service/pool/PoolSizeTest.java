package com.abatalev.demo.etcdhikari.service.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PoolSizeTest {

    @Test
    @DisplayName("минимум не задан — следует за максимумом")
    void minimumIdleFollowsMaximumPoolSizeWhenNotSet() {
        PoolSize.Normalized normalized = new PoolSize(10, null).normalize();

        assertThat(normalized.size().minimumIdle()).isEqualTo(10);
        assertThat(normalized.warnings()).isEmpty();
    }

    @Test
    @DisplayName("незаданный максимум — это нулевой пул, а не ошибка")
    void missingMaximumMeansNoPool() {
        PoolSize.Normalized normalized = new PoolSize(null, null).normalize();

        assertThat(normalized.size().maximumPoolSize()).isZero();
        assertThat(normalized.size().minimumIdle()).isZero();
        assertThat(normalized.warnings()).isEmpty();
    }

    @Test
    @DisplayName("минимум без максимума понижается до нуля с предупреждением")
    void minimumIdleWithoutMaximumIsClampedToZero() {
        PoolSize.Normalized normalized = new PoolSize(null, 5).normalize();

        assertThat(normalized.size().maximumPoolSize()).isZero();
        assertThat(normalized.size().minimumIdle()).isZero();
        assertThat(normalized.warnings())
                .containsExactly("minimumIdle=5 > maximumPoolSize=0 -> понижен до 0");
    }

    @Test
    void minimumIdleGreaterThanMaxIsClampedWithWarning() {
        PoolSize.Normalized normalized = new PoolSize(5, 20).normalize();

        assertThat(normalized.size().minimumIdle()).isEqualTo(5);
        assertThat(normalized.warnings()).anyMatch(w -> w.contains("minimumIdle"));
    }

    @Test
    void negativePoolSizeIsRejected() {
        assertThatThrownBy(() -> new PoolSize(-1, -1).normalize())
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("maximumPoolSize");
    }

    @Test
    void overMaxPoolSizeIsRejected() {
        assertThatThrownBy(() -> new PoolSize(201, 201).normalize())
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("maximumPoolSize");
    }

    @Test
    void overMaxMinimumIdleIsRejected() {
        assertThatThrownBy(() -> new PoolSize(10, PoolSize.POOL_SIZE_MAX + 1).normalize())
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("minimumIdle");
    }

    @Test
    void explicitMinimumIdleIsKeptAsIs() {
        PoolSize.Normalized normalized = new PoolSize(30, 5).normalize();

        assertThat(normalized.size().maximumPoolSize()).isEqualTo(30);
        assertThat(normalized.size().minimumIdle()).isEqualTo(5);
        assertThat(normalized.warnings()).isEmpty();
    }

    @Test
    @DisplayName("diff описывает только размер и не выдаёт изменений на одинаковых значениях")
    void diffDescribesOnlySizeChanges() {
        assertThat(new PoolSize(10, 10).diff(new PoolSize(25, 25)))
                .containsExactly("maximumPoolSize: 10 -> 25", "minimumIdle: 10 -> 25");
        assertThat(new PoolSize(10, 10).diff(new PoolSize(10, 5)))
                .containsExactly("minimumIdle: 10 -> 5");
        List<String> none = new PoolSize(10, 10).diff(new PoolSize(10, 10));
        assertThat(none).isEmpty();
    }
}