package com.abatalev.demo.etcdhikari.service.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.opentelemetry.api.common.AttributeKey;
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
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

import com.abatalev.demo.etcdhikari.service.config.DbProperties;
import com.abatalev.demo.etcdhikari.service.metrics.HikariEventMetrics;
import com.abatalev.demo.etcdhikari.service.metrics.PoolCounters;
import com.abatalev.demo.etcdhikari.service.otel.MechanismSpans;

/**
 * DataSource поверх HikariCP, который никогда не пересоздаётся "просто так".
 *
 * <p>Из etcd приходит только размер, он применяется на живой пул через {@link HikariConfigMXBean}.
 * Пересоздание — единственный выход из закрытого состояния: пул сняли нулевым размером, пришла
 * ненулевая доля. Сменить цель соединения на живом пуле нечем: она задана локально
 * ({@link DbProperties}) на весь процесс, а MBean её всё равно не принимает.
 *
 * <p>Локального размера пула нет вовсе: пул не создаётся, пока не пришёл первый конфиг из etcd.
 * Целевой максимум 0 (резерв холодной группы, снятие конфигурации) честно закрывает пул с дренажом
 * активных соединений — {@code dataSource} становится {@code null}, а память о последнем размере
 * остаётся в {@link #applied}.
 *
 * <p>Инстанс намеренно реализует {@link DataSource}, а не отдаёт {@code HikariDataSource} наружу:
 * тогда {@code JdbcTemplate} и менеджер транзакций Spring следуют за подменой пула сами.
 */
public class ManagedPool implements DataSource, AutoCloseable {

    // свой логгер, чтобы события пула не терялись в общем потоке spring-логов
    private static final org.slf4j.Logger log = LoggerFactory.getLogger("hikari");

    /** Сколько ждать, пока пул доберёт коннекты при увеличении (иначе добьёт HouseKeeper). */
    private static final Duration EAGER_FILL_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Порог события ожидания выдачи соединения.
     *
     * <p>Ждать приходится и на пустом месте: единицы миллисекунд — обычное дело даже при свободном
     * пуле. Такие ожидания событием не считаются, иначе под нагрузкой в трассы шёл бы поток
     * бесполезных следов, а механизм наблюдения перестал бы быть разборным. Дальше этой границы
     * ожидание — уже содержательный признак: либо пул насыщен, либо соединение создаётся.
     */
    private static final long ACQUIRE_TRACE_MIN_WAIT_MS = 25;

    /** Цель соединения и таймауты: локальны на весь процесс, из etcd не приходят. */
    private final DbProperties db;

    private final boolean eagerFillOnResize;
    private final Duration drainTimeout;

    /** Счётчики решений пула; null — наблюдение выключено. */
    private final PoolCounters counters;

    /** Приборы событий пула (SPI HikariCP); null — наблюдение выключено. */
    private final HikariEventMetrics eventMetrics;

    /** Трассы событий механизма; NOOP — наблюдение выключено. */
    private final MechanismSpans spans;

    private final AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();
    private final AtomicReference<PoolSize> applied = new AtomicReference<>();
    private final AtomicReference<String> lastReason = new AtomicReference<>("startup");
    private final AtomicLong lastChangeAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong createdAt = new AtomicLong();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger resizeCount = new AtomicInteger();
    private final AtomicInteger recreateCount = new AtomicInteger();

    /**
     * Поколение пула, которое сейчас освобождает соединения (дренируется перед закрытием).
     *
     * <p>Ссылка {@link #dataSource} на это поколение уже не указывает — иначе наблюдение врало бы:
     * соединения ещё открыты в базе, а пул уже «исчез». Пока здесь не null, наблюдение обязано
     * учитывать его соединения, иначе инстанс рапортует об освобождении, которого не было.
     */
    private final AtomicReference<Draining> draining = new AtomicReference<>();

    /** Монитор смены конфигурации: публикация долга ждёт его, а не крутит таймер вслепую. */
    private final java.util.concurrent.locks.ReentrantLock stateLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition appliedChanged = stateLock.newCondition();
    private long appliedVersion;

    /** Освобождаемое поколение пула: снимок MBean'а и его номер. */
    private record Draining(HikariPoolMXBean pool, int generation) {}

    /** Событие смены конфигурации: изменилось то, что видно снаружи (размер, пул, дренаж). */
    private void publishStateChange() {
        stateLock.lock();
        try {
            appliedVersion++;
            appliedChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Ждёт смены конфигурации начиная с наблюдённой версии.
     *
     * @param since версия, после которой ждём; 0 — ждём следующего изменения
     * @param timeoutMs сколько ждать, если изменений не будет
     * @return текущая версия
     */
    public long awaitAppliedChange(long since, long timeoutMs) throws InterruptedException {
        stateLock.lock();
        try {
            if (appliedVersion != since) {
                return appliedVersion;
            }
            appliedChanged.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return appliedVersion;
        } finally {
            stateLock.unlock();
        }
    }

    /** Версия наблюдённого состояния; меняется при каждом применении конфигурации. */
    public long appliedVersion() {
        return appliedVersion;
    }

    /**
     * Пул не создаётся на старте: размера нет ни в etcd, ни локально, поэтому и ждать нечего.
     * Локальная конфигурация проверяется сразу — иначе неверный таймаут всплыл бы через событие
     * etcd и выглядел бы отказом провизёра, а не собственной ошибкой процесса.
     */
    public ManagedPool(DbProperties db, boolean eagerFillOnResize, Duration drainTimeout, PoolCounters counters,
            MechanismSpans spans) {
        this.db = db;
        this.eagerFillOnResize = eagerFillOnResize;
        this.drainTimeout = drainTimeout == null ? Duration.ZERO : drainTimeout;
        this.counters = counters;
        this.spans = spans == null ? MechanismSpans.NOOP : spans;
        this.eventMetrics = counters == null ? null : new HikariEventMetrics(counters.registry());
        db.validate().forEach(w -> log.warn("[startup] нормализация конфига: {}", w));
        log.info("размер пула приходит только из etcd: локального размера нет, пул не создан");
        lastReason.set("startup: пула нет (ждём конфигурацию из etcd)");
    }

    /**
     * Применяет новый конфиг.
     *
     * @return что именно произошло: пул создан, пересоздан, ресайзнут, закрыт или конфиг не изменился
     */
    public ApplyResult apply(PoolSize desired, String reason) {
        try {
            // «Запрошено» пишется до нормализации и как есть: если конфиг отклонится, именно исходные
            // значения и есть событие. Размер nullable, поэтому «не задано» пишется как UNSET (-1):
            // ноль у него означает «пул снят», а не «не задано», и смешивать эти два смысла в признаке
            // нельзя. Разыменование null здесь уронило бы apply() целиком — пул не создаётся.
            return spans.callQuietly("pool.config.apply", b -> b
                    .setAttribute("config.reason", reason)
                    .setAttribute("pool.generation", generation.get())
                    .setAttribute("pool.max.requested", orUnset(desired.maximumPoolSize()))
                    .setAttribute("pool.min_idle.requested", orUnset(desired.minimumIdle()))
                    // Прежний размер — тоже часть события: по требованию спеки переход виден в трассе
                    // как «было -> стало», без обращения к журналу за второй половиной пары.
                    .setAttribute("pool.max.before", maxBefore())
                    .setAttribute("pool.min_idle.before", minIdleBefore()),
                    span -> {
                        ApplyResult result = applyInternal(desired, reason);
                        // Итог дописывается в тот же span: решение и его исход — одно событие,
                        // и по журналу видно ровно то же, что по трассе.
                        span.setAttribute("config.outcome", result.outcome().name());
                        span.setAttribute(AttributeKey.stringArrayKey("config.changes"),
                                result.changes());
                        PoolSize now = result.size();
                        span.setAttribute("pool.max.after", now == null ? 0 : now.maximumPoolSize());
                        span.setAttribute("pool.min_idle.after", now == null ? 0 : now.minimumIdle());
                        span.setAttribute("pool.generation.after", generation.get());
                        return result;
                    });
        } finally {
            // Сигнал наружу: размер пула, поколение или долг изменились — публикация должна узнать.
            publishStateChange();
        }
    }

    /** Признак «не задано» для nullable-размера: -1, потому что 0 у него значит «пул снят». */
    private static int orUnset(Integer value) {
        return value == null ? -1 : value;
    }

    /** Размер до применения: 0 — пула не было (или он уже был снят). */
    private int maxBefore() {
        PoolSize current = applied.get();
        return current == null ? 0 : orUnset(current.maximumPoolSize());
    }

    private int minIdleBefore() {
        PoolSize current = applied.get();
        return current == null ? 0 : orUnset(current.minimumIdle());
    }

    private synchronized ApplyResult applyInternal(PoolSize desired, String reason) {
        PoolSize target;
        List<String> warnings;
        try {
            PoolSize.Normalized normalized = desired.normalize();
            target = normalized.size();
            warnings = new ArrayList<>(normalized.warnings());
            warnings.addAll(db.warningsFor(target));
        } catch (InvalidSettingsException e) {
            PoolSize appliedSize = applied.get();
            count(c -> c.configRejected());
            log.error("[{}] конфиг отклонён ({}), остаёмся на предыдущих значениях: {}",
                    reason, e.getMessage(), appliedSize == null ? "<пула нет>" : appliedSize);
            return new ApplyResult(Outcome.REJECTED, List.of("rejected: " + e.getMessage()), null);
        }

        PoolSize current = applied.get();
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
            // Пула ещё нет: первый принятый конфиг создаёт его.
            warnings.forEach(w -> log.warn("[{}] нормализация конфига: {}", reason, w));
            create(target, reason, false);
            count(c -> c.configApplied());
            return new ApplyResult(Outcome.CREATED, List.of(), applied.get());
        }

        HikariDataSource ds = dataSource.get();
        if (ds == null) {
            // Пул был закрыт (нулевой целевой размер), память о последнем размере осталась в applied:
            // поднимаем пул заново. Это единственный путь пересоздания: цель соединения задана
            // локально и на живом пуле не меняется, а MBean такие параметры всё равно не принимает.
            List<String> changes = current.diff(target);
            warnings.forEach(w -> log.warn("[{}] нормализация конфига: {}", reason, w));
            create(target, reason, true);
            recreateCount.incrementAndGet();
            count(c -> c.recreated());
            count(c -> c.configApplied());
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
        if (target.maximumPoolSize() > current.maximumPoolSize()) {
            count(c -> c.resizeGrow());
        } else {
            count(c -> c.resizeShrink());
        }
        count(c -> c.configApplied());
        lastReason.set(reason);
        lastChangeAt.set(System.currentTimeMillis());
        log.info("[{}] пул '{}' обновлён на лету: {} | now: max={} minIdle={} total={}",
                reason, db.getPoolName(), String.join(", ", changes), target.maximumPoolSize(),
                target.minimumIdle(), runtime().total());
        return new ApplyResult(Outcome.RESIZED, changes, target);
    }

    /**
     * Неосвобождённое сжатие: сколько соединений держим сверх текущего потолка.
     *
     * <p>Чистая функция — её держит и наблюдение, и публикация в etcd, чтобы обе величины
     * считались одинаково. Пока конфигурации нет, потолок нулевой и удерживаемое считается
     * неосвобождённым целиком.
     *
     * <p>Результат неотрицателен по построению, и ошибка оценки односторонняя: завышенное
     * значение лишь придерживает рост флота, поэтому потолок бюджета защищается даже при
     * неточном отчёте инстанса.
     *
     * @param ceiling текущий размер пула инстанса (0 — конфигурации нет)
     * @param held открытые соединения обоих поколений (текущего и освобождаемого)
     */
    public static int unreleasedConnections(int ceiling, int held) {
        return Math.max(0, held - ceiling);
    }

    /**
     * Вытесняет idle-соединения, оказавшиеся сверх текущего потолка.
     *
     * <p>Нужно для хвоста сжатия. Соединение, создание которого было начато до сжатия, может
     * появиться в пуле уже после того, как инстанс отрапортовал об освобождении места: счётчик
     * HikariCP растёт в момент завершения создания, и отменить уже начатое нельзя. Само по себе
     * лишнее соединение не опасно — следующая публикация снова увидит неосвобождённое сжатие и
     * провижёр придержит рост, — но без вытеснения оно живёт, пока его не заберёт очередь
     * ожидающих, а её может не быть: тогда пул держит соединений больше потолка и флот теряет
     * место навсегда.
     *
     * @return {@code true}, если вытеснение выполнялось
     */
    public boolean evictAboveCeiling() {
        HikariDataSource ds = dataSource.get();
        if (ds == null || runtime().unreleasedConnections() <= 0) {
            return false;
        }
        try {
            ds.getHikariPoolMXBean().softEvictConnections();
            return true;
        } catch (RuntimeException e) {
            log.warn("вытеснение сверх потолка не удалось: {}", e.toString());
            return false;
        }
    }

    /** Честное закрытие пула (целевой максимум 0): дренаж активных, потом close. */
    private ApplyResult closePool(PoolSize target, PoolSize current, String reason) {
        HikariDataSource ds = dataSource.getAndSet(null);
        if (ds != null) {
            // Поколение уходит в дренаж ДО закрытия: пока идёт дренаж, наблюдение обязано видеть
            // его соединения, иначе инстанс рапортует об освобождении, которого не было.
            beginDrain(ds, generation.get());
            try {
                closeQuietly(ds, "config_removed");
            } finally {
                endDrain();
            }
            log.info("[{}] пул '{}' закрыт: размер {} -> 0, активные соединения дренированы",
                    reason, db.getPoolName(), current.maximumPoolSize());
        }
        applied.set(target);
        count(c -> c.configApplied());
        lastReason.set(reason);
        lastChangeAt.set(System.currentTimeMillis());
        return new ApplyResult(Outcome.CLOSED,
                List.of("maximumPoolSize: " + current.maximumPoolSize() + " -> 0"), target);
    }

    /** Поколение пула переходит в дренаж: его соединения ещё открыты и должны быть видны. */
    private void beginDrain(HikariDataSource ds, int gen) {
        if (ds == null || ds.isClosed()) {
            return;
        }
        draining.set(new Draining(ds.getHikariPoolMXBean(), gen));
    }

    private void endDrain() {
        draining.set(null);
    }

    /** Порядок важен: при уменьшении сначала minimumIdle, иначе получим minIdle > max. */
    private void applyOnLivePool(PoolSize from, PoolSize to, String reason) {
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

    private void setMinimumIdle(HikariConfigMXBean mx, PoolSize from, PoolSize to, String reason) {
        setQuietly(reason, "minimumIdle", from.minimumIdle(), to.minimumIdle(), () -> mx.setMinimumIdle(to.minimumIdle()));
    }

    private void setMaximumPoolSize(HikariConfigMXBean mx, PoolSize from, PoolSize to, String reason) {
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
    private void eagerFill(PoolSize target, String reason) {
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

    /**
     * Создание пула — либо первого, либо нового поколения вместо снятого.
     *
     * <p>Предусловие: пула нет, {@link #dataSource} — {@code null}. Все три вызова это гарантируют
     * (старт, первый принятый конфиг при локальном максимуме 0, ненулевая доля после снятия), и
     * закрывать тут нечего: дренаж живого пула — дело {@link #closePool} и {@link #close()}.
     *
     * <p>{@code recreation} различает первое создание и подъём после снятия конфигурации: разные
     * имена событий механизма и разная строка в журнале, потому что разбор «куда делись соединения»
     * смотрит именно на второй случай.
     */
    private void create(PoolSize size, String reason, boolean recreation) {
        int previousGeneration = generation.get();
        spans.runQuietly(recreation ? "pool.recreate" : "pool.create", b -> b
                .setAttribute("config.reason", reason)
                .setAttribute("pool.name", db.getPoolName())
                .setAttribute("pool.max", size.maximumPoolSize())
                .setAttribute("pool.min_idle", size.minimumIdle())
                .setAttribute("pool.generation.from", previousGeneration)
                .setAttribute("pool.generation.to", previousGeneration + 1)
                .setAttribute("pool.drain.timeout_ms", drainTimeout.toMillis()),
                () -> createPool(size, reason, recreation));
    }

    private void createPool(PoolSize size, String reason, boolean recreation) {
        // Конфигурация HikariCP собирается в одном месте (DbProperties.toHikariConfig): локальные
        // значения и размер из etcd, без промежуточной модели, которая могла бы с ними разойтись.
        HikariConfig config = db.toHikariConfig(size);
        // События пула (ожидание выдачи, удержание, открытие, таймауты) — единственное место, где
        // пул рождается, поэтому прибор подключается здесь и не наследует прошлых поколений.
        if (eventMetrics != null) {
            config.setMetricsTrackerFactory(eventMetrics);
        }

        HikariDataSource fresh = new HikariDataSource(config);

        dataSource.set(fresh);
        applied.set(size);
        generation.incrementAndGet();
        createdAt.set(System.currentTimeMillis());
        lastChangeAt.set(System.currentTimeMillis());
        lastReason.set(reason);

        if (recreation) {
            log.info("[{}] HikariCP '{}' создан заново после снятия конфигурации: max={} minIdle={}",
                    reason, db.getPoolName(), size.maximumPoolSize(), size.minimumIdle());
        } else {
            log.info("[{}] HikariCP '{}' создан: jdbc={} user={} max={} minIdle={} connectionTimeout={}ms",
                    reason, db.getPoolName(), db.getJdbcUrl(), db.getUsername(),
                    size.maximumPoolSize(), size.minimumIdle(), db.getConnectionTimeoutMs());
        }
    }

    /** Счётчик решения — только если наблюдение включено. */
    private void count(Consumer<PoolCounters> action) {
        if (counters != null) {
            action.accept(counters);
        }
    }

    private void closeQuietly(HikariDataSource ds, String cause) {
        drain(ds, cause);
        try {
            ds.close();
        } catch (RuntimeException e) {
            log.warn("не удалось закрыть предыдущий пул: {}", e.toString());
        }
    }

    /**
     * HikariCP закрывает активные соединения принудительно — запросы в полёте упадут с 08006.
     * Поэтому перед close() вытесняем idle и ждём (недолго), пока доработают активные.
     *
     * <p>Ожидание — событие механизма: по нему видно, сколько соединений держал уходящий пул и
     * успел ли дренаж. Признак {@code pool.drain.cause} различает поводы: снятие конфигурации и
     * остановка процесса — трафик они ломают по-разному.
     */
    private void drain(HikariDataSource ds, String cause) {
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
            spans.callQuietly("pool.drain", b -> b
                    .setAttribute("pool.name", ds.getPoolName())
                    .setAttribute("pool.drain.cause", cause)
                    .setAttribute("pool.drain.active", active)
                    .setAttribute("pool.drain.timeout_ms", drainTimeout.toMillis()),
                    span -> {
                        try {
                            pool.softEvictConnections();
                            long deadline = System.nanoTime() + drainTimeout.toNanos();
                            while (pool.getActiveConnections() > 0 && System.nanoTime() < deadline) {
                                Thread.sleep(20);
                            }
                            int left = pool.getActiveConnections();
                            span.setAttribute("pool.drain.left", left);
                            span.setAttribute("pool.drain.timed_out", left > 0);
                            if (left > 0) {
                                log.warn("{} активных соединений не успели доработать за {} — закрываем принудительно",
                                        left, drainTimeout);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException e) {
                            log.debug("drain не удался: {}", e.toString());
                        }
                        return null;
                    });
        } catch (RuntimeException e) {
            log.debug("drain не удался: {}", e.toString());
        }
    }

    /** Применённый размер пула; null — конфигурация ещё не приходила. */
    public PoolSize size() {
        return applied.get();
    }

    /** Таймаут ожидания соединения: локальное значение, из etcd не приходит. */
    public long connectionTimeoutMs() {
        return db.getConnectionTimeoutMs();
    }

    public Runtime runtime() {
        HikariDataSource ds = dataSource.get();
        Draining d = draining.get();
        // Дренируемое поколение читаем первым и только пока ссылка на него жива: после close()
        // MBean отдаёт нули, и такие нули не должны выглядеть как «соединений нет».
        int drainingTotal = 0;
        int drainingActive = 0;
        int drainingGeneration = 0;
        if (d != null) {
            try {
                drainingTotal = d.pool().getTotalConnections();
                drainingActive = d.pool().getActiveConnections();
                drainingGeneration = d.generation();
            } catch (RuntimeException e) {
                // MBean закрытого пула может отказать — это не повод врать наблюдению о нуле.
                drainingTotal = 0;
                drainingActive = 0;
                drainingGeneration = d.generation();
            }
        }

        if (ds == null) {
            // Пула нет (ещё не создан или закрыт), но освобождается он или нет — показываем правду.
            PoolSize s = applied.get();
            int held = drainingTotal;
            int ceiling = s == null ? 0 : s.maximumPoolSize();
            return new Runtime(
                    s == null ? "нет пула" : db.getPoolName(),
                    generation.get(),
                    true,
                    held, drainingActive, 0, 0,
                    ceiling,
                    s == null ? 0 : s.minimumIdle(),
                    unreleasedConnections(ceiling, held),
                    drainingGeneration,
                    drainingTotal,
                    createdAt.get(),
                    lastChangeAt.get(),
                    lastReason.get(),
                    resizeCount.get(),
                    recreateCount.get());
        }
        HikariPoolMXBean pool = ds.getHikariPoolMXBean();
        int total = pool.getTotalConnections() + drainingTotal;
        int active = pool.getActiveConnections() + drainingActive;
        int ceiling = ds.getHikariConfigMXBean().getMaximumPoolSize();
        return new Runtime(
                ds.getPoolName(),
                generation.get(),
                ds.isClosed(),
                total,
                active,
                pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(),
                ceiling,
                ds.getHikariConfigMXBean().getMinimumIdle(),
                // Долг: сколько соединений держим сверх потолка. Из неотрицательного и честного —
                // инстанс, завышающий своё значение, лишь придерживает рост флота.
                unreleasedConnections(ceiling, total),
                drainingGeneration,
                drainingTotal,
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
            beginDrain(ds, generation.get());
            try {
                closeQuietly(ds, "stop");
            } finally {
                endDrain();
                publishStateChange();
            }
        }
    }

    // ---------------- DataSource ----------------

    @Override
    public Connection getConnection() throws SQLException {
        HikariDataSource ds = require();
        // Замер ожидания идёт здесь, а не в контроллере: точку вызова знает только пул, и под
        // нагрузкой очередь видна именно тут.
        int awaitingBefore = ds.getHikariPoolMXBean().getThreadsAwaitingConnection();
        long startNanos = System.nanoTime();
        try {
            return ds.getConnection();
        } finally {
            long waitedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (waitedMs >= ACQUIRE_TRACE_MIN_WAIT_MS) {
                spans.event("pool.acquire.wait", b -> b
                        .setAttribute("pool.name", ds.getPoolName())
                        .setAttribute("pool.acquire.wait_ms", waitedMs)
                        .setAttribute("pool.acquire.threads_awaiting", awaitingBefore)
                        .setAttribute("pool.acquire.max", ds.getMaximumPoolSize())
                        .setAttribute("pool.acquire.total", ds.getHikariPoolMXBean().getTotalConnections())
                        .setAttribute("pool.acquire.idle", ds.getHikariPoolMXBean().getIdleConnections()));
            }
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // Выдача по логину и паролю пулом не применяется: путь оставлен как был, события ожидания
        // здесь не заводятся — на стенде им не пользуются, а ждать их нечего.
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
     * Пока пула нет (конфигурация ещё не приходила), вспомогательные методы DataSource-контракта не
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

    public record ApplyResult(Outcome outcome, List<String> changes, PoolSize size) {}

    /** Срез состояния пула в конкретный момент. */
    public record Runtime(
            String poolName,
            int generation,
            boolean closed,
            /** открытые соединения обоих поколений: текущего и освобождаемого */
            int total,
            int active,
            int idle,
            int threadsAwaitingConnection,
            int maximumPoolSize,
            int minimumIdle,
            /** неосвобождённое сжатие: сколько соединений держим сверх потолка */
            int unreleasedConnections,
            /** номер освобождаемого поколения; 0 — освобождаемого пула нет */
            int drainingGeneration,
            /** соединения освобождаемого поколения; 0 — освобождаемого пула нет */
            int drainingTotal,
            long createdAtEpochMs,
            long lastChangeEpochMs,
            String lastChangeReason,
            int resizeCount,
            int recreationCount) {}
}
