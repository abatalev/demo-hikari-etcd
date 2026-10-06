package com.abatalev.demo.etcdhikari.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abatalev.demo.etcdhikari.service.pool.InvalidSettingsException;
import com.abatalev.demo.etcdhikari.service.pool.PoolSize;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DbPropertiesTest {

    @Test
    @DisplayName("дефолты проходят проверку и не дают предупреждений")
    void defaultsAreValid() {
        assertThat(new DbProperties().validate()).isEmpty();
    }

    @Test
    void missingJdbcUrlIsRejected() {
        DbProperties db = new DbProperties();
        db.setJdbcUrl(null);

        assertThatThrownBy(db::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("jdbcUrl");
    }

    @Test
    void nonJdbcUrlIsRejected() {
        DbProperties db = new DbProperties();
        db.setJdbcUrl("postgres://localhost/demo");

        assertThatThrownBy(db::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("jdbcUrl");
    }

    @Test
    void blankUsernameAndPoolNameAreRejected() {
        DbProperties blankUser = new DbProperties();
        blankUser.setUsername(" ");
        assertThatThrownBy(blankUser::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("username");

        DbProperties blankName = new DbProperties();
        blankName.setPoolName("");
        assertThatThrownBy(blankName::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("poolName");
    }

    @Test
    @DisplayName("пустой пароль — законное значение, отсутствующий — нет")
    void emptyPasswordIsAllowedButMissingIsRejected() {
        DbProperties empty = new DbProperties();
        empty.setPassword("");
        assertThat(empty.validate()).isEmpty();

        DbProperties missing = new DbProperties();
        missing.setPassword(null);
        assertThatThrownBy(missing::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("password");
    }

    @Test
    void tooSmallConnectionTimeoutIsRejected() {
        DbProperties db = new DbProperties();
        db.setConnectionTimeoutMs(100L);

        assertThatThrownBy(db::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("connectionTimeoutMs");
    }

    @Test
    @DisplayName("таймаут простоя и жизни принимают 0 как отключение и не принимают промежуточное")
    void zeroIsTheOnlyValueOutsideRange() {
        DbProperties disabled = new DbProperties();
        disabled.setIdleTimeoutMs(0L);
        disabled.setMaxLifetimeMs(0L);
        disabled.setLeakDetectionThresholdMs(0L);
        assertThat(disabled.validate()).isEmpty();

        DbProperties tooSmall = new DbProperties();
        tooSmall.setIdleTimeoutMs(9_999L);
        assertThatThrownBy(tooSmall::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("idleTimeoutMs");

        DbProperties tooShortLifetime = new DbProperties();
        tooShortLifetime.setMaxLifetimeMs(29_999L);
        assertThatThrownBy(tooShortLifetime::validate)
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("maxLifetimeMs");
    }

    @Test
    @DisplayName("порог утечек не меньше таймаута ожидания — предупреждение, а не отказ")
    void leakDetectionAboveConnectionTimeoutWarns() {
        DbProperties db = new DbProperties();
        db.setConnectionTimeoutMs(5_000L);
        db.setLeakDetectionThresholdMs(6_000L);

        assertThat(db.validate())
                .containsExactly("leakDetectionThresholdMs >= connectionTimeoutMs, возможны ложные срабатывания");
    }

    @Test
    @DisplayName("таймаут простоя не применим, когда минимум равен максимуму")
    void idleTimeoutWarnsWhenMinimumEqualsMaximum() {
        DbProperties db = new DbProperties();

        assertThat(db.warningsFor(new PoolSize(25, 25))).containsExactly(
                "idleTimeout не применим: minimumIdle == maximumPoolSize");
        // минимум меньше максимума — таймаут простоя применяется
        assertThat(db.warningsFor(new PoolSize(25, 5))).isEmpty();
        // пула нет — ворчать не о чем
        assertThat(db.warningsFor(new PoolSize(0, 0))).isEmpty();
    }

    @Test
    @DisplayName("конфигурация HikariCP собирается из локальных значений и размера из etcd")
    void buildsHikariConfigFromLocalValuesAndSize() {
        DbProperties db = new DbProperties();
        db.setPoolName("service-a-group-1-1");
        db.setJdbcUrl("jdbc:postgresql://postgres-a:5432/app");
        db.setUsername("app");
        db.setPassword("secret");
        db.setConnectionTimeoutMs(3_000L);
        db.setIdleTimeoutMs(600_000L);
        db.setMaxLifetimeMs(1_800_000L);
        db.setValidationTimeoutMs(5_000L);
        db.setLeakDetectionThresholdMs(0L);
        db.setInitializationFailTimeoutMs(-1L);
        db.setRegisterMbeans(false);

        HikariConfig config = db.toHikariConfig(new PoolSize(25, 5));

        assertThat(config.getPoolName()).isEqualTo("service-a-group-1-1");
        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://postgres-a:5432/app");
        assertThat(config.getUsername()).isEqualTo("app");
        assertThat(config.getPassword()).isEqualTo("secret");
        assertThat(config.getMaximumPoolSize()).isEqualTo(25);
        assertThat(config.getMinimumIdle()).isEqualTo(5);
        assertThat(config.getConnectionTimeout()).isEqualTo(3_000L);
        assertThat(config.getIdleTimeout()).isEqualTo(600_000L);
        assertThat(config.getMaxLifetime()).isEqualTo(1_800_000L);
        assertThat(config.getValidationTimeout()).isEqualTo(5_000L);
        assertThat(config.getLeakDetectionThreshold()).isEqualTo(0L);
        assertThat(config.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(config.isRegisterMbeans()).isFalse();
        // application_name нужен, чтобы сборщики сессий postgres различали пулы
        assertThat(config.getDataSourceProperties()).containsEntry("ApplicationName", "service-a-group-1-1");
    }
}