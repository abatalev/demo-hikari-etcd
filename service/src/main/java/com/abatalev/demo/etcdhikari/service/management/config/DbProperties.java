package com.abatalev.demo.etcdhikari.service.management.config;

import com.abatalev.demo.etcdhikari.service.management.pool.InvalidSettingsException;
import com.abatalev.demo.etcdhikari.service.management.pool.PoolSize;
import com.zaxxer.hikari.HikariConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Локальная конфигурация пула: цель соединения и таймауты. Меняется только перезапуском, поэтому
 * в etcd её нет — из хранилища приходит исключительно размер ({@link PoolSize}).
 *
 * <p>Здесь же единственное место, где собирается {@link HikariConfig}: держать рядом с ним вторую
 * модель настроек — значит гарантированно получить расхождение между проверенным и применённым.
 */
@ConfigurationProperties(prefix = "pool.db")
public class DbProperties {

    private static final long CONNECTION_TIMEOUT_MIN_MS = 250;
    private static final long CONNECTION_TIMEOUT_MAX_MS = 600_000;
    private static final long IDLE_TIMEOUT_MIN_MS = 10_000;
    private static final long IDLE_TIMEOUT_MAX_MS = 3_600_000;
    private static final long MAX_LIFETIME_MIN_MS = 30_000;
    private static final long MAX_LIFETIME_MAX_MS = 3_600_000;
    private static final long VALIDATION_TIMEOUT_MIN_MS = 250;
    private static final long VALIDATION_TIMEOUT_MAX_MS = 60_000;
    private static final long LEAK_DETECTION_MIN_MS = 2_000;
    private static final long LEAK_DETECTION_MAX_MS = 600_000;

    private String jdbcUrl = "jdbc:postgresql://localhost:5432/demo";
    private String username = "app";
    private String password = "app";
    private String poolName = "pool-service";

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
     * Сколько ждать, пока доработают активные соединения перед закрытием старого пула. 0 = закрывать
     * сразу, не ждя.
     */
    private Duration drainOnRecreateTimeout = Duration.ofSeconds(3);

    private boolean registerMbeans = true;

    /**
     * Статический размер пула (используется при pool.etcd.enabled=false).
     */
    private Integer maximumPoolSize = 10;

    /**
     * Статический минимум idle-соединений (используется при pool.etcd.enabled=false).
     */
    private Integer minimumIdle = 10;

    /**
     * Проверка локальной конфигурации. Жёсткие нарушения -> исключение (контекст падает на старте:
     * молчаливое применение неверного таймаута хуже, чем отказ подняться). Мягкие -> предупреждения
     * для журнала.
     *
     * <p>Проверка живёт здесь, а не в HikariCP, потому что диапазоны и обязательность — наш контракт
     * (см. спеку config-validation): свой HikariCP проверяет в момент создания пула и по-английски,
     * а часть сочетаний не предупреждает вовсе.
     */
    public List<String> validate() {
        String url = require(jdbcUrl, "jdbcUrl");
        if (!url.startsWith("jdbc:")) {
            throw new InvalidSettingsException("jdbcUrl должен начинаться с 'jdbc:', получено: " + url);
        }
        require(username, "username");
        if (password == null) {
            throw new InvalidSettingsException("password обязателен (пустая строка = вход без пароля)");
        }
        require(poolName, "poolName");

        long connectionTimeout = longInRange(connectionTimeoutMs, "connectionTimeoutMs",
                CONNECTION_TIMEOUT_MIN_MS, CONNECTION_TIMEOUT_MAX_MS);
        longZeroOrRange(idleTimeoutMs, "idleTimeoutMs", IDLE_TIMEOUT_MIN_MS, IDLE_TIMEOUT_MAX_MS);
        longZeroOrRange(maxLifetimeMs, "maxLifetimeMs", MAX_LIFETIME_MIN_MS, MAX_LIFETIME_MAX_MS);
        longInRange(validationTimeoutMs, "validationTimeoutMs", VALIDATION_TIMEOUT_MIN_MS, VALIDATION_TIMEOUT_MAX_MS);
        long leakDetection = longZeroOrRange(leakDetectionThresholdMs, "leakDetectionThresholdMs",
                LEAK_DETECTION_MIN_MS, LEAK_DETECTION_MAX_MS);

        List<String> warnings = new ArrayList<>();
        if (leakDetection > 0 && leakDetection >= connectionTimeout) {
            warnings.add("leakDetectionThresholdMs >= connectionTimeoutMs, возможны ложные срабатывания");
        }
        return warnings;
    }

    /**
     * Предупреждения, которые зависят от размера пула: таймаут простоя не применим, когда минимум
     * равен максимуму. Локальное значение одно, поэтому проверять эту связь имеет смысл там, где
     * пришёл размер, — на каждом применении конфигурации.
     */
    public List<String> warningsFor(PoolSize size) {
        if (idleTimeoutMs > 0 && size.maximumPoolSize() > 0
                && Objects.equals(size.minimumIdle(), size.maximumPoolSize())) {
            return List.of("idleTimeout не применим: minimumIdle == maximumPoolSize");
        }
        return List.of();
    }

    /**
     * Единственное место, где собирается конфигурация HikariCP: локальные значения плюс размер из
     * etcd. Вызывается на каждое создание пула, поэтому собирать заново — правильно.
     *
     * @param size нормализованный размер (см. {@link PoolSize#normalize()})
     */
    public HikariConfig toHikariConfig(PoolSize size) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(size.maximumPoolSize());
        config.setMinimumIdle(size.minimumIdle());
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setValidationTimeout(validationTimeoutMs);
        config.setLeakDetectionThreshold(leakDetectionThresholdMs);
        config.setInitializationFailTimeout(initializationFailTimeoutMs);
        config.setRegisterMbeans(registerMbeans);
        // чтобы считать свои сессии в pg_stat_activity
        config.addDataSourceProperty("ApplicationName", poolName);
        return config;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        return value;
    }

    private static long longInRange(Long value, String field, long min, long max) {
        if (value == null) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        if (value < min || value > max) {
            throw new InvalidSettingsException(field + "=" + value + " вне диапазона [" + min + ".." + max + "]");
        }
        return value;
    }

    private static long longZeroOrRange(Long value, String field, long min, long max) {
        if (value == null) {
            throw new InvalidSettingsException(field + " обязателен");
        }
        if (value == 0L) {
            return 0L;
        }
        if (value < min || value > max) {
            throw new InvalidSettingsException(field + "=" + value + " вне диапазона [0 или " + min + ".." + max + "]");
        }
        return value;
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
}
