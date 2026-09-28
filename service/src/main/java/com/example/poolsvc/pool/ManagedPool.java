package com.example.poolsvc.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * DataSource поверх HikariCP, который никогда не пересоздаётся "просто так".
 *
 * <p>Изменения конфигурации из etcd применяются на живой пул через {@link HikariConfigMXBean}
 * (maximumPoolSize/minimumIdle/timeouts). Полное пересоздание — только когда поменялась
 * "целевая" часть конфига (jdbcUrl/логин/пароль/имя пула), которую MBean менять не умеет.
 *
 * <p>Инстанс намеренно реализует {@link DataSource}, а не отдаёт {@code HikariDataSource} наружу:
 * тогда {@code JdbcTemplate} и менеджер транзакций Spring следуют за подменой пула сами.
 */
public class ManagedPool implements DataSource, AutoCloseable {

    // свой логгер, чтобы события пула не терялись в общем потоке spring-логов
    private static final org.slf4j.Logger log = LoggerFactory.getLogger("hikari");

    /** Сколько ждать, пока пул доберёт коннекты при увеличении (иначе добьёт HouseKeeper). */
    private static final Duration EAGER_FILL_TIMEOUT = Duration.ofSeconds(2);

    private final long initializationFailTimeoutMs;
    private final boolean registerMbeans;
    private final boolean eagerFillOnResize;
    private final Duration drainTimeout;

    private final AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();
    private final AtomicReference<HikariSettings> applied = new AtomicReference<>();
    private final AtomicReference<String> lastReason = new AtomicReference<>("startup");
    private final AtomicLong lastChangeAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong createdAt = new AtomicLong();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger resizeCount = new AtomicInteger();
    private final AtomicInteger recreateCount = new AtomicInteger();

    public ManagedPool(HikariSettings defaults, Long initializationFailTimeoutMs, boolean registerMbeans,
            boolean eagerFillOnResize, Duration drainTimeout) {
        this.initializationFailTimeoutMs = initializationFailTimeoutMs == null ? -1L : initializationFailTimeoutMs;
        this.registerMbeans = registerMbeans;
        this.eagerFillOnResize = eagerFillOnResize;
        this.drainTimeout = drainTimeout == null ? Duration.ZERO : drainTimeout;
        HikariSettings.Normalized startup = defaults.normalize();
        startup.warnings().forEach(w -> log.warn("[startup] нормализация конфига: {}", w));
        create(startup.settings(), "startup");
    }

    /**
     * Применяет новый конфиг.
     *
     * @return что именно произошло: пул создан, пересоздан, ресайзнут или конфиг не изменился
     */
    public synchronized ApplyResult apply(HikariSettings desired, String reason) {
        HikariSettings target;
        List<String> warnings;
        try {
            HikariSettings.Normalized normalized = desired.normalize();
            target = normalized.settings();
            warnings = normalized.warnings();
        } catch (InvalidSettingsException e) {
            log.error("[{}] конфиг отклонён ({}), остаёмся на предыдущих значениях: {}",
                    reason, e.getMessage(), applied.get().redacted());
            return new ApplyResult(Outcome.REJECTED, List.of("rejected: " + e.getMessage()), null);
        }

        HikariSettings current = applied.get();
        if (!current.sameTarget(target)) {
            // Смена цели (jdbcUrl/креды) — MBean так не умеет, только пересоздание пула.
            List<String> changes = current.diff(target);
            warnings.forEach(w -> log.warn("[{}] нормализация конфига: {}", reason, w));
            create(target, reason);
            return new ApplyResult(Outcome.RECREATED, changes, applied.get());
        }

        List<String> changes = current.diff(target);
        if (changes.isEmpty()) {
            log.debug("[{}] конфиг не изменился", reason);
            return new ApplyResult(Outcome.UNCHANGED, List.of(), current);
        }
        // предупреждения показываем только когда реально что-то поменялось, иначе они сыпятся на каждый put
        warnings.forEach(w -> log.warn("[{}] нормализация конфига: {}", reason, w));

        applyOnLivePool(current, target, reason);
        applied.set(target);
        resizeCount.incrementAndGet();
        lastReason.set(reason);
        lastChangeAt.set(System.currentTimeMillis());
        log.info("[{}] пул '{}' обновлён на лету: {} | now: max={} minIdle={} total={}",
                reason, target.poolName(), String.join(", ", changes), target.maximumPoolSize(),
                target.minimumIdle(), runtime().total());
        return new ApplyResult(Outcome.RESIZED, changes, target);
    }

    /** Порядок важен: при уменьшении сначала minimumIdle, иначе получим minIdle > max. */
    private void applyOnLivePool(HikariSettings from, HikariSettings to, String reason) {
        HikariDataSource ds = dataSource.get();
        HikariConfigMXBean mx = ds.getHikariConfigMXBean();
        boolean shrinking = to.maximumPoolSize() < from.maximumPoolSize();

        if (shrinking) {
            setMinimumIdle(mx, from, to, reason);
            setMaximumPoolSize(mx, from, to, reason);
        } else {
            setMaximumPoolSize(mx, from, to, reason);
            setMinimumIdle(mx, from, to, reason);
        }

        setQuietly(reason, "connectionTimeoutMs", from.connectionTimeoutMs(), to.connectionTimeoutMs(),
                () -> mx.setConnectionTimeout(to.connectionTimeoutMs()));
        setQuietly(reason, "idleTimeoutMs", from.idleTimeoutMs(), to.idleTimeoutMs(),
                () -> mx.setIdleTimeout(to.idleTimeoutMs()));
        setQuietly(reason, "maxLifetimeMs", from.maxLifetimeMs(), to.maxLifetimeMs(),
                () -> mx.setMaxLifetime(to.maxLifetimeMs()));
        setQuietly(reason, "validationTimeoutMs", from.validationTimeoutMs(), to.validationTimeoutMs(),
                () -> mx.setValidationTimeout(to.validationTimeoutMs()));
        setQuietly(reason, "leakDetectionThresholdMs", from.leakDetectionThresholdMs(), to.leakDetectionThresholdMs(),
                () -> mx.setLeakDetectionThreshold(to.leakDetectionThresholdMs()));

        if (shrinking) {
            // лимит применился сразу, а лишние idle-коннекты иначе висели бы до следующего
            // прохода HouseKeeper (~30 c): softEvict закрывает всё сверх minimumIdle
            try {
                ds.getHikariPoolMXBean().softEvictConnections();
            } catch (RuntimeException e) {
                log.warn("[{}] softEvictConnections не удался: {}", reason, e.toString());
            }
        }

        int delta = to.maximumPoolSize() - runtime().total();
        if (!shrinking && delta > 0 && eagerFillOnResize) {
            eagerFill(to, reason);
        }
    }

    private void setMinimumIdle(HikariConfigMXBean mx, HikariSettings from, HikariSettings to, String reason) {
        setQuietly(reason, "minimumIdle", from.minimumIdle(), to.minimumIdle(), () -> mx.setMinimumIdle(to.minimumIdle()));
    }

    private void setMaximumPoolSize(HikariConfigMXBean mx, HikariSettings from, HikariSettings to, String reason) {
        setQuietly(reason, "maximumPoolSize", from.maximumPoolSize(), to.maximumPoolSize(),
                () -> mx.setMaximumPoolSize(to.maximumPoolSize()));
    }

    private void setQuietly(String reason, String field, Object from, Object to, Runnable setter) {
        if (Objects.equals(from, to)) {
            return;
        }
        try {
            setter.run();
        } catch (RuntimeException e) {
            log.error("[{}] не удалось применить {} ({} -> {}), оставляем прежнее: {}",
                    reason, field, from, to, e.toString());
        }
    }

    /**
     * Демо-помощь: добиваем пул до нового размера сразу, а не через HouseKeeper (он раз в ~30 c).
     * Нужно именно <em>одновременно</em> держать соединения: если брать и сразу возвращать,
     * HikariCP отдаст то же самое idle-соединение и новое не создаст.
     * Дедлайн нужен, чтобы не блокировать watch, если пул занят нагрузкой.
     */
    private void eagerFill(HikariSettings target, String reason) {
        HikariDataSource ds = dataSource.get();
        int before = runtime().total();
        if (before >= target.maximumPoolSize()) {
            return;
        }
        List<Connection> held = new ArrayList<>();
        long deadline = System.nanoTime() + EAGER_FILL_TIMEOUT.toNanos();
        try {
            while (runtime().total() < target.maximumPoolSize() && System.nanoTime() < deadline) {
                held.add(ds.getConnection());
            }
        } catch (SQLException e) {
            log.warn("[{}] eager fill: добили только часть пула ({}), остальное добавится по надобности",
                    reason, e.getMessage());
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.debug("[{}] не удалось вернуть соединение после eager fill: {}", reason, e.toString());
                }
            }
        }
        int after = runtime().total();
        if (after < target.maximumPoolSize()) {
            log.info("[{}] eager fill: расширил пул с {} до {} из {} — дальше добивать будет HouseKeeper по надобности",
                    reason, before, after, target.maximumPoolSize());
        } else {
            log.info("[{}] eager fill: пул расширен с {} до {} коннектов одним изменением", reason, before, after);
        }
    }

    private void create(HikariSettings settings, String reason) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(settings.poolName());
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(settings.minimumIdle());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setIdleTimeout(settings.idleTimeoutMs());
        config.setMaxLifetime(settings.maxLifetimeMs());
        config.setValidationTimeout(settings.validationTimeoutMs());
        config.setLeakDetectionThreshold(settings.leakDetectionThresholdMs());
        config.setInitializationFailTimeout(initializationFailTimeoutMs);
        config.setRegisterMbeans(registerMbeans);
        // чтобы считать свои сессии в pg_stat_activity
        config.addDataSourceProperty("ApplicationName", settings.poolName());

        HikariDataSource previous = dataSource.get();
        HikariDataSource fresh = new HikariDataSource(config);

        dataSource.set(fresh);
        applied.set(settings);
        generation.incrementAndGet();
        if (previous != null) {
            recreateCount.incrementAndGet();
            closeQuietly(previous);
        }
        createdAt.set(System.currentTimeMillis());
        lastChangeAt.set(System.currentTimeMillis());
        lastReason.set(reason);

        if (previous == null) {
            log.info("[{}] HikariCP '{}' создан: jdbc={} user={} max={} minIdle={} connectionTimeout={}ms",
                    reason, settings.poolName(), settings.jdbcUrl(), settings.username(),
                    settings.maximumPoolSize(), settings.minimumIdle(), settings.connectionTimeoutMs());
        } else {
            log.info("[{}] HikariCP '{}' пересоздан (сменилась цель: jdbcUrl/креды/имя)", reason, settings.poolName());
        }
    }

    private void closeQuietly(HikariDataSource ds) {
        drain(ds);
        try {
            ds.close();
        } catch (RuntimeException e) {
            log.warn("не удалось закрыть предыдущий пул: {}", e.toString());
        }
    }

    /**
     * HikariCP закрывает активные соединения принудительно — запросы в полёте упадут с 08006.
     * Поэтому перед close() вытесняем idle и ждём (недолго), пока доработают активные.
     */
    private void drain(HikariDataSource ds) {
        if (drainTimeout.isZero() || ds.isClosed()) {
            return;
        }
        try {
            HikariPoolMXBean pool = ds.getHikariPoolMXBean();
            if (pool.getActiveConnections() == 0) {
                return;
            }
            int active = pool.getActiveConnections();
            log.info("старый пул '{}' закрывается: ждём {} активных соединений (до {})",
                    ds.getPoolName(), active, drainTimeout);
            pool.softEvictConnections();
            long deadline = System.nanoTime() + drainTimeout.toNanos();
            while (pool.getActiveConnections() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            if (pool.getActiveConnections() > 0) {
                log.warn("{} активных соединений не успели доработать за {} — закрываем принудительно",
                        pool.getActiveConnections(), drainTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("drain не удался: {}", e.toString());
        }
    }

    public HikariSettings settings() {
        return applied.get();
    }

    public Runtime runtime() {
        HikariDataSource ds = dataSource.get();
        HikariPoolMXBean pool = ds.getHikariPoolMXBean();
        return new Runtime(
                ds.getPoolName(),
                generation.get(),
                ds.isClosed(),
                pool.getTotalConnections(),
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(),
                ds.getHikariConfigMXBean().getMaximumPoolSize(),
                ds.getHikariConfigMXBean().getMinimumIdle(),
                createdAt.get(),
                lastChangeAt.get(),
                lastReason.get(),
                resizeCount.get(),
                recreateCount.get());
    }

    @Override
    public void close() {
        HikariDataSource ds = dataSource.getAndSet(null);
        if (ds != null) {
            closeQuietly(ds);
        }
    }

    // ---------------- DataSource ----------------

    @Override
    public Connection getConnection() throws SQLException {
        return require().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return require().getConnection(username, password);
    }

    private HikariDataSource require() {
        HikariDataSource ds = dataSource.get();
        if (ds == null) {
            throw new IllegalStateException("пул закрыт");
        }
        return ds;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return require().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        require().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        require().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return require().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger(ManagedPool.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : require().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || require().isWrapperFor(iface);
    }

    public enum Outcome { CREATED, RESIZED, RECREATED, UNCHANGED, REJECTED }

    public record ApplyResult(Outcome outcome, List<String> changes, HikariSettings settings) {}

    /** Срез состояния пула в конкретный момент. */
    public record Runtime(
            String poolName,
            int generation,
            boolean closed,
            int total,
            int active,
            int idle,
            int threadsAwaitingConnection,
            int maximumPoolSize,
            int minimumIdle,
            long createdAtEpochMs,
            long lastChangeEpochMs,
            String lastChangeReason,
            int resizeCount,
            int recreationCount) {}
}
