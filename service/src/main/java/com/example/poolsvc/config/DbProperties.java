package com.example.poolsvc.config;

import com.example.poolsvc.pool.HikariSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Локальные дефолты (источник "фолбэк", когда etcd недоступен или ключ не задан). Размер пула
 * задаётся исключительно etcd: {@code maximumPoolSize} по умолчанию 0, поэтому без конфигурации
 * пул не создаётся вообще.
 */
@ConfigurationProperties(prefix = "pool.db")
public class DbProperties {

    private String jdbcUrl = "jdbc:postgresql://localhost:5432/demo";
    private String username = "app";
    private String password = "app";
    private String poolName = "pool-service";

    /**
     * Размер пула приходит только из etcd (провижер делит сервисный бюджет); локальный дефолт
     * жёстко 0 — пула нет, пока не пришла конфигурация. 0 из etcd источник отклоняет (REJECTED).
     */
    private Integer maximumPoolSize = 0;
    /** null = "держаться за maximumPoolSize" ( HikariCP так и делает по умолчанию). */
    private Integer minimumIdle = null;
    private Long connectionTimeoutMs = 30_000L;
    private Long idleTimeoutMs = 600_000L;
    private Long maxLifetimeMs = 1_800_000L;
    private Long validationTimeoutMs = 5_000L;
    private Long leakDetectionThresholdMs = 0L;

    /**
     * -1 = не падать на старте, если БД недоступна: пул поднимется и добьёт коннекты сам.
     * Для прототипа/стенда это удобно, в проде ставьте положительное значение.
     */
    private Long initializationFailTimeoutMs = -1L;

    /**
     * Демо-фича: после увеличения maximumPoolSize сразу добить пул до нового размера,
     * иначе рост виден только под нагрузкой (или через ~30 c, когда отработает HouseKeeper).
     * В проде обычно выключают: EAGER_FILL=false.
     */
    private boolean eagerFillOnResize = true;

    /**
     * Сколько ждать, пока доработают активные соединения перед закрытием старого пула
     * (актуально только для смены jdbcUrl/кредов). 0 = закрывать сразу, не ждя.
     */
    private Duration drainOnRecreateTimeout = Duration.ofSeconds(3);

    private boolean registerMbeans = true;

    public HikariSettings toSettings() {
        return new HikariSettings(
                jdbcUrl,
                username,
                password,
                poolName,
                maximumPoolSize,
                minimumIdle,
                connectionTimeoutMs,
                idleTimeoutMs,
                maxLifetimeMs,
                validationTimeoutMs,
                leakDetectionThresholdMs);
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }

    public Integer getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(Integer maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public Integer getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(Integer minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public Long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(Long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public Long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(Long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public Long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }

    public void setMaxLifetimeMs(Long maxLifetimeMs) {
        this.maxLifetimeMs = maxLifetimeMs;
    }

    public Long getValidationTimeoutMs() {
        return validationTimeoutMs;
    }

    public void setValidationTimeoutMs(Long validationTimeoutMs) {
        this.validationTimeoutMs = validationTimeoutMs;
    }

    public Long getLeakDetectionThresholdMs() {
        return leakDetectionThresholdMs;
    }

    public void setLeakDetectionThresholdMs(Long leakDetectionThresholdMs) {
        this.leakDetectionThresholdMs = leakDetectionThresholdMs;
    }

    public Long getInitializationFailTimeoutMs() {
        return initializationFailTimeoutMs;
    }

    public void setInitializationFailTimeoutMs(Long initializationFailTimeoutMs) {
        this.initializationFailTimeoutMs = initializationFailTimeoutMs;
    }

    public boolean isEagerFillOnResize() {
        return eagerFillOnResize;
    }

    public void setEagerFillOnResize(boolean eagerFillOnResize) {
        this.eagerFillOnResize = eagerFillOnResize;
    }

    public Duration getDrainOnRecreateTimeout() {
        return drainOnRecreateTimeout;
    }

    public void setDrainOnRecreateTimeout(Duration drainOnRecreateTimeout) {
        this.drainOnRecreateTimeout = drainOnRecreateTimeout;
    }

    public boolean isRegisterMbeans() {
        return registerMbeans;
    }
    public void setRegisterMbeans(boolean registerMbeans) {
        this.registerMbeans = registerMbeans;
    }
}
