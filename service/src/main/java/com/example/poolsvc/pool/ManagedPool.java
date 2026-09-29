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
 * <p>Размер пула задаётся только конфигурацией из etcd: локальный дефолт максимума 0, и пул не
 * создаётся, пока не пришёл первый конфиг. Целевой максимум 0 (резерв холодной группы, снятие
 * конфигурации) честно закрывает пул с дренажом активных соединений — {@code dataSource}
 * становится {@code null}, а память о последнем конфиге остаётся в {@link #applied}.
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
        if (startup.settings().maximumPoolSize() > 0) {
            create(startup.settings(), "startup");
        } else {
            log.info("локальный максимум пула = 0: пул не создан, размер придёт из конфигурации etcd");
            lastReason.set("startup: пула нет (размер 0, ждём конфигурацию из etcd)");
        }
    }

    /**
     * Применяет новый конфиг.
     *
     * @return что именно произошло: пул создан, пересоздан, ресайзнут, закрыт или конфиг не изменился
     */
    public synchronized ApplyResult apply(HikariSettings desired, String reason) {
        HikariSettings target;
        List<String> warnings;
        try {
            HikariSettings.Normalized normalized = desired.normalize();
            target = normalized.settings();
            warnings = normalized.warnings();
        } catch (InvalidSettingsException e) {
            HikariSettings appliedSettings = applied.get();
            log.error("[{}] конфиг отклонён ({}), остаёмся на предыдущих значениях: {}",
                    reason, e.getMessage(), appliedSettings == null ? "<пула нет>" : appliedSettings.redacted());
            return new ApplyResult(Outcome.REJECTED, List.of("rejected: " + e.getMessage()), null);
        }

        HikariSettings current = applied.get();
        if (target.maximumPoolSize() == 0) {
            // Снятие пула (холодная группа R=0, инстанс без доли). Дренаж обязателен:
            // HikariDataSource.close() убивает активные соединения и ломает запросы в полёте.
            if (current == null) {
                // Пул и так не существует — повторный ноль ничего не делает.
                return new ApplyResult(Outcome.UNCHANGED, List.of(), target);
            }
            return closePool(target, current, reason);
        }

        if (current == null) {
            // Пула ещё нет (старт с локальным максимумом 0): первый принятый конфиг создаёт его.
            warnings.forEach(w -> log.warn("[{}] нормализация конфига: {}", reason, w));
            create(target, reason);
            return new ApplyResult(Outcome.CREATED, List.of(), applied.get());
        }

        HikariDataSource ds = dataSource.get();
        boolean targetChanged = !current.sameTarget(target);
        if (targetChanged || ds == null) {
            // Смена цели (jdbcUrl/креды/имя) MBean не умеет — только пересоздание. ds == null бывает
            // после закрытия (память о последнем конфиге остаётся в applied) — поднимаем пул заново.
            List<String> changes = current.diff(target);
            warnings.forEach(w -> log.warn("[{}] нормализация конфига: {}", reason, w));
            create(target, reason);
            return new ApplyResult(targetChanged && ds != null ? Outcome.RECREATED : Outcome.CREATED,
                    changes, applied.get());
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

    /** Честное закрытие пула (целевой максимум 0): дренаж активных, потом close. */
    private ApplyResult closePool(HikariSettings target, HikariSettings current, String reason) {
        HikariDataSource ds = dataSource.getAndSet(null);
        if (ds != null) {
            closeQuietly(ds);
            log.info("[{}] пул '{}' закрыт: размер {} -> 0, активные соединения дренированы",
                    reason, target.poolName(), current.maximumPoolSize());
        }
        applied.set(target);
        lastReason.set(reason);
        lastChangeAt.set(System.currentTimeMillis());
        return new ApplyResult(Outcome.CLOSED,
                List.of("maximumPoolSize: " + current.maximumPoolSize() + " -> 0"), target);
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
        if (ds == null) {
            // Пула нет (ещё не создан или закрыт): честный срез «закрыт, размер 0».
            HikariSettings s = applied.get();
            return new Runtime(
                    s == null ? "нет пула" : s.poolName(),
                    generation.get(),
                    true,
                    0, 0, 0, 0,
                    s == null ? 0 : s.maximumPoolSize(),
                    s == null ? 0 : s.minimumIdle(),
                    createdAt.get(),
                    lastChangeAt.get(),
                    lastReason.get(),
                    resizeCount.get(),
                    recreateCount.get());
        }
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

    /**
     * Пока пула нет (старт с размером 0), вспомогательные методы DataSource-контракта не должны
     * падать — Spring и актуатор ходят в них ещё до первого конфига из etcd.
     */
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        HikariDataSource ds = dataSource.get();
        return ds == null ? null : ds.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        HikariDataSource ds = dataSource.get();
        if (ds != null) {
            ds.setLogWriter(out);
        }
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        HikariDataSource ds = dataSource.get();
        if (ds != null) {
            ds.setLoginTimeout(seconds);
        }
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        HikariDataSource ds = dataSource.get();
        return ds == null ? 0 : ds.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger(ManagedPool.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        HikariDataSource ds = dataSource.get();
        if (ds == null) {
            throw new SQLException("пул закрыт");
        }
        return ds.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        // Отсутствие пула не должно ронять интроспекцию (например, актуаторный db-индикатор).
        if (iface.isInstance(this)) {
            return true;
        }
        HikariDataSource ds = dataSource.get();
        return ds != null && ds.isWrapperFor(iface);
    }

    public enum Outcome { CREATED, RESIZED, RECREATED, CLOSED, UNCHANGED, REJECTED }

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
