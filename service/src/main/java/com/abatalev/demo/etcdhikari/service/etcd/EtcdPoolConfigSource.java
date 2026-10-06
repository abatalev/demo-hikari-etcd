package com.abatalev.demo.etcdhikari.service.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.abatalev.demo.etcdhikari.service.config.EtcdProperties;
import com.abatalev.demo.etcdhikari.service.metrics.PoolCounters;
import com.abatalev.demo.etcdhikari.service.otel.MechanismSpans;
import com.abatalev.demo.etcdhikari.service.pool.InvalidSettingsException;
import com.abatalev.demo.etcdhikari.service.pool.ManagedPool;

/**
 * Единственный источник конфигурации пула — etcd.
 *
 * <p>Схема работы: снимок пути (get) + watch с revision+1. Разрыв между снимком и watch'ем
 * невозможен, потому что watch стартует с той же ревизии. Любой обрыв (compaction, рестарт etcd,
 * потеря сети) приводит к новому снимку — просто и всегда корректно для пути из ~10 ключей.
 *
 * <p>Путь конфигурации ({@code {root}/services/{service}/groups/{group}/instances/{instance}/hikari/})
 * собирается из сегментов в конструкторе через {@link EtcdKeyPath}; незаполненные сегменты при
 * включённом источнике прекращают запуск с внятной причиной.
 *
 * <p>Гейт трафика: пока конфигурация не получена (в пути нет ни одного распознанного ключа),
 * сервис остаётся запущенным, но не готов принимать трафик — готовность (readiness) закрыта,
 * живость (liveness) от etcd не зависит. Гейт открывается первым распознанным ключом и
 * закрывается, когда распознанных ключей не остаётся (провижер снял конфигурацию — инстанс,
 * например, потерял долю бюджета), после чего снова открывается при возврате ключей. Закрытие
 * происходит только по реальным событиям удаления при живом etcd: недоступность хранилища ни
 * трафик, ни готовность не останавливает — в этом случае события не приходят вовсе.
 *
 * <p>Регистрация инстанса: при включённом источнике на старте в etcd создаётся узел
 * {@code {root}/.../instances/{instance}} (имя ключа = имя инстанса), удерживаемый арендой
 * (TTL + keepalive). Узел живёт на уровень выше {@code hikari/}, поэтому конфиг-воркер его не
 * видит — на готовность и гейт регистрация не влияет. Штатная остановка отзывает аренду
 * (узел исчезает сразу), падение процесса — по истечении TTL.
 */
@Component
public class EtcdPoolConfigSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EtcdPoolConfigSource.class);

    /**
     * Ключи, значение которых не показывается в статусе источника ни при каких условиях.
     * Сейчас из etcd приходят только размер и минимум, поэтому список пуст по сути: учётные данные
     * берутся локально. Оставлен как защита от значения, которое в etcd всё же появится.
     */
    private static final Set<String> MASKED_KEYS = Set.of("password");

    private final ManagedPool pool;
    private final EtcdProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final PoolCounters counters;

    /** Трассы событий механизма; NOOP — наблюдение выключено. */
    private final MechanismSpans spans;

    /** Путь ключей этого экземпляра; null, когда источник выключен. */
    private final String path;

    /** Узел регистрации инстанса (путь без хвоста {@code hikari/}); null, когда источник выключен. */
    private final String nodePath;

    /**
     * Ключ публикации неосвобождённого сжатия; null, когда источник выключен.
     *
     * <p>Лежит рядом с узлом регистрации и вне префикса {@code hikari/}, поэтому конфиг-воркер его
     * не видит: ни снимок, ни watch его не касаются, и опубликованная величина никогда не
     * попадает в применяемую конфигурацию.
     */
    private final String unreleasedPath;

    private final Map<String, String> keys = new ConcurrentHashMap<>();
    private final Map<String, String> reportedProblems = new ConcurrentHashMap<>();
    private final Set<String> warnedUnknownKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    /** Гейт трафика: открывается после получения конфигурации и закрывается при её снятии. */
    private final AtomicBoolean trafficAllowed = new AtomicBoolean();
    private final AtomicLong lastRevision = new AtomicLong();
    private final AtomicLong lastEventAt = new AtomicLong();
    private final AtomicLong applyCount = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<String> notReadyReason = new AtomicReference<>();
    /** та же причина закрытым набором — для метрик, где свободный текст недопустим */
    private final AtomicReference<NotReadyCause> notReadyCause = new AtomicReference<>();
    private final AtomicReference<ManagedPool.Outcome> lastOutcome = new AtomicReference<>();
    /** Номер текущей аренды узла регистрации; -1, когда аренды нет. */
    private final AtomicLong registrationLease = new AtomicLong(-1);
    private final AtomicBoolean emptyPrefixLogged = new AtomicBoolean();

    private volatile Client client;
    private volatile Thread watcherThread;
    private volatile Thread registrationThread;
    private volatile Thread publicationThread;

    public EtcdPoolConfigSource(ManagedPool pool, EtcdProperties properties,
            ApplicationEventPublisher eventPublisher, PoolCounters counters, MechanismSpans spans) {
        this.pool = pool;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.counters = counters;
        this.spans = spans == null ? MechanismSpans.NOOP : spans;
        if (properties.isEnabled()) {
            this.path = EtcdKeyPath.build(properties.getRoot(), properties.getService(),
                    properties.getGroup(), properties.getInstance());
            this.nodePath = EtcdKeyPath.nodePath(properties.getRoot(), properties.getService(),
                    properties.getGroup(), properties.getInstance());
            this.unreleasedPath = EtcdKeyPath.unreleasedConnectionsPath(properties.getRoot(),
                    properties.getService(), properties.getGroup(), properties.getInstance());
        } else {
            this.path = null;
            this.nodePath = null;
            this.unreleasedPath = null;
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String path() {
        return path;
    }

    /** Причина закрытой готовности; null, когда источник выключен или трафик открыт. */
    public String notReadyReason() {
        return notReadyReason.get();
    }

    /**
     * Причина закрытой готовности закрытым набором.
     *
     * <p>Метрикам нельзя отдавать свободный текст: строка с путём и ошибкой etcd стала бы
     * безграничным набором значений. Поэтому причина живёт как перечисление, а человеческий
     * текст собирается из него — иначе текст и метка разъедутся.
     *
     * @return {@code null}, когда трафик открыт или источник выключен
     */
    public NotReadyCause notReadyCause() {
        return notReadyCause.get();
    }

    /** Жива ли подписка: цикл watch запущен, даже если последний снимок не удался. */
    public boolean isWatchActive() {
        return running.get();
    }

    /** Отвечает ли etcd: последний снимок получен, обрыв сбрасывает признак. */
    public boolean isConnected() {
        return connected.get();
    }

    /** Открыт ли трафик: false, пока конфигурация не получена (при включённом источнике). */
    public boolean isTrafficAllowed() {
        return !properties.isEnabled() || trafficAllowed.get();
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("etcd-конфиг выключен (pool.etcd.enabled=false), работаем на локальных дефолтах");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // Стартуем с закрытой готовностью: до первого распознанного ключа трафик не принимаем.
        eventPublisher.publishEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        watcherThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-config-watch")
                .unstarted(this::watchLoop);
        watcherThread.start();
        // Регистрация — отдельный поток: keepalive не должен блокировать конфиг-watch.
        registrationThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-registration")
                .unstarted(this::registrationLoop);
        registrationThread.start();
        // Публикация неосвобождённого сжатия — тоже отдельный поток: она ждёт смены конфигурации
        // и опрашивает пул, пока место не освободится, и не должна блокировать watch.
        publicationThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-drain-publication")
                .unstarted(this::publicationLoop);
        publicationThread.start();
    }

    /**
     * Штатная остановка: сперва освобождаем соединения, потом снимаем регистрацию.
     *
     * <p>Порядок обязателен. Регистрация держит долю бюджета: пока узел жив, провижёр не отдаст
     * его место никому. Если снять регистрацию первым, провижёр немедленно перераспределит долю,
     * а наш пул ещё держит соединения — сумма по флоту превысит бюджет на всё время дренажа.
     * И наоборот: дренаж не должен ждать etcd, поэтому узел остаётся видимым до конца.
     *
     * <p>Потоки останавливаем первыми, чтобы во время дренажа не пришло новое событие watch и не
     * открылся пул заново. Публикацию тоже глушим: во время остановки величина долга не имеет
     * смысла — узел всё равно исчезнет вместе с ключом.
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread w = watcherThread;
        if (w != null) {
            w.interrupt();
        }
        Thread r = registrationThread;
        if (r != null) {
            r.interrupt();
        }
        Thread p = publicationThread;
        if (p != null) {
            p.interrupt();
        }
        // Дренаж идёт при живом узле регистрации: место освобождается прежде, чем доля уйдёт.
        // close() дренирует активные соединения с таймаутом и только потом закрывает пул.
        if (w != null) {
            try {
                w.join(properties.getCallTimeout().toMillis() + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pool.close();
        log.info("пул освобождён перед снятием регистрации");
        // Отзываем аренду после дренажа; пробуем дважды, чтобы поймать аренду,
        // которую поток успел выдать, но ещё не опубликовал до первого захода.
        revokeRegistration();
        if (r != null) {
            try {
                r.join(properties.getCallTimeout().toMillis() + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        revokeRegistration();
        Client c = client;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException e) {
                log.warn("не удалось закрыть etcd-клиент: {}", e.toString());
            }
        }
    }

    /** Стартуем последними и останавливаем первыми: сеть etcd не должна мешать shutdown Spring. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void watchLoop() {
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        while (running.get()) {
            try {
                Client c = client();
                long revision = snapshot(c);
                watch(c, revision + 1);
                // watch завершился штатно (compaction/reconnect с нашей стороны) -> переснапшотим
                backoffMs = properties.getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                connected.set(false);
                lastError.set(e.toString());
                log.warn("etcd недоступен/ошибка watch ({}), повтор через {} мс", e.toString(), backoffMs);
                refreshNotReadyReason();
                if (!sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    private Client client() {
        Client existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = Client.builder()
                        .endpoints(properties.getEndpoints().toArray(String[]::new))
                        .build();
                log.info("etcd watch стартует: endpoints={} path={}", properties.getEndpoints(), path);
            }
            return client;
        }
    }

    /**
     * Регистрация инстанса в etcd: узел {@code .../instances/{instance}} на аренде.
     *
     * <p>Цикл: grant(ttl) → put(узел, пустое значение, lease) → keepalive каждые ttl/3.
     * Любая ошибка (обрыв etcd, истёкшая аренда) возвращает цикл к перерегистрации с backoff —
     * узел восстанавливается, как только хранилище снова отвечает. Штатный стоп аренду
     * отзывает в {@link #stop()} — здесь при прерывании просто выходим.
     */
    private void registrationLoop() {
        long ttlSeconds = properties.getRegistrationTtl().getSeconds();
        long keepaliveMs = Math.max(1, ttlSeconds / 3) * 1000L;
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        while (running.get()) {
            try {
                Client c = client();
                Lease leaseClient = c.getLeaseClient();
                long lease = leaseClient.grant(ttlSeconds)
                        .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .getID();
                registrationLease.set(lease);
                c.getKVClient()
                        .put(ByteSequence.from(nodePath, StandardCharsets.UTF_8), ByteSequence.EMPTY,
                                PutOption.newBuilder().withLeaseId(lease).build())
                        .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
                log.info("инстанс зарегистрирован: узел {} (lease={}, ttl={}s)", nodePath, lease, ttlSeconds);
                while (running.get()) {
                    try {
                        leaseClient.keepAliveOnce(lease)
                                .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        log.warn("keepalive узла регистрации оборвался ({}), перерегистрируюсь",
                                e.toString());
                        if (!sleep(backoffMs)) {
                            return;
                        }
                        break;
                    }
                    if (!sleep(keepaliveMs)) {
                        return;
                    }
                }
                backoffMs = properties.getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("регистрация не удалась ({}), повтор через {} мс", e.toString(), backoffMs);
                if (!sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    /**
     * Публикация неосвобождённого сжатия: сколько соединений инстанс держит сверх потолка.
     *
     * <p>Пока долг равен нулю, поток спит на смене конфигурации — ждать нечего, публикаций нет
     * и не должно быть. Как только применение конфигурации состоялось, берём долг у пула: он
     * положителен ровно тогда, когда сжатие ещё не освободило соединения, и обновляется по мере
     * их возврата. Терминальный ноль публикуем безусловно — на нём держится разблокировка роста.
     *
     * <p>Пока долг ненулевой, опрашиваем пул по таймеру: на пути сжатия активные соединения
     * возвращаются уже после применения конфигурации, и события в etcd об их возврате не приходят.
     * Публикация по событию, а не по расписанию: значение меняется вместе с числом соединений.
     *
     * <p>Запись идёт по аренде узла регистрации, поэтому ключ исчезает вместе с узлом и провизёр
     * не считает его осиротевшим. Отдельного запроса от провизёра не требуется.
     */
    private void publicationLoop() {
        Integer lastPublished = null;
        // Аренда, под которой записана последняя публикация. Ключ живёт на аренде узла
        // регистрации и умирает вместе с ней: после обрыва etcd аренда истекает, ключ исчезает, а
        // инстанс при переподключении получает новую. Прежняя запись в etcd не пережила
        // переподключения, поэтому и в памяти её нельзя считать опубликованной — иначе ноль,
        // однажды записанный, больше не повторится, провижёр навсегда увидит худший случай и не
        // раздаст место флоту.
        long publishedUnderLease = -1;
        long version = -1;
        long idleMs = properties.getPublishIdleInterval().toMillis();
        long pollMs = properties.getPublishPollInterval().toMillis();
        int quantum = properties.getPublishQuantum();

        while (running.get()) {
            try {
                // Ждём смены конфигурации, пока публиковать нечего. Таймаут — страховка на случай,
                // если сигнал потеряется: повторная проверка дёшева, а молчание опасно.
                long previous = version;
                version = pool.awaitAppliedChange(version, idleMs);
                boolean ceilingChanged = version != previous;

                int debt = pool.runtime().unreleasedConnections();
                long lease = registrationLease.get();
                boolean leaseChanged = lease > 0 && lease != publishedUnderLease;
                Integer publish = DrainDebtPolicy.toPublish(debt, lastPublished, ceilingChanged,
                        leaseChanged, quantum);
                if (publish != null && publishDebt(publish, debt)) {
                    // Запоминаем только действительно записанное: иначе ранняя неудача (клиент
                    // ещё не создан) навсегда заглушила бы публикацию, а провижёр — видел бы
                    // худший случай и не выдавал бы место флоту.
                    lastPublished = publish;
                    publishedUnderLease = lease;
                }
                if (debt == 0) {
                    // Место освобождено (или сжатия не было) — дальше снова ждём конфигурации.
                    continue;
                }
                // Долг ненулевой: освобождение идёт постепенно, наблюдаем за ним. Заодно
                // вытесняем всё, что оказалось сверх потолка: публикация уже ушла, а лишнее
                // соединение иначе дожило бы до появления ожидающего и держало место занятым.
                pool.evictAboveCeiling();
                version = pool.appliedVersion();
                if (!sleep(pollMs)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                lastError.set(e.toString());
                log.warn("публикация неосвобождённого сжатия не удалась: {}", e.toString());
                if (!sleep(pollMs)) {
                    return;
                }
            }
        }
    }

    /**
     * Пишет величину долга по аренде узла регистрации.
     *
     * <p>Аренда та же, что у узла регистрации: публикация обязана исчезнуть вместе с инстансом,
     * иначе после его смерти провижёр до упора считал бы, что место занято.
     *
     * @return true, если значение действительно записано
     */
    private boolean publishDebt(int value, int debt) {
        Client c = client;
        if (c == null || unreleasedPath == null) {
            return false;
        }
        long lease = registrationLease.get();
        if (lease <= 0) {
            // Аренда ещё не выдана. Ключ без аренды пережил бы инстанс и вечно держал бы место
            // занятым, поэтому ждём регистрации, а не пишем «на всякий случай».
            return false;
        }
        try {
            // Событие механизма: запись величины, которой провижёр ждёт, чтобы перераспределить
            // место. По трассе видно, что место освобождается и сколько раз публикация стоила.
            spans.run("etcd.drain_debt.publish", b -> b
                    .setAttribute("etcd.key", unreleasedPath)
                    .setAttribute("etcd.value", value)
                    .setAttribute("pool.debt", debt)
                    .setAttribute("pool.max", pool.runtime().maximumPoolSize())
                    .setAttribute("pool.total", pool.runtime().total())
                    .setAttribute("etcd.lease", lease),
                    () -> c.getKVClient()
                            .put(
                                    ByteSequence.from(unreleasedPath, StandardCharsets.UTF_8),
                                    ByteSequence.from(Integer.toString(value), StandardCharsets.UTF_8),
                                    PutOption.newBuilder().withLeaseId(lease).build())
                            .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS));
            log.info("опубликовано неосвобождённое сжатие: {} (потолок {}, удерживается {})",
                    value, pool.runtime().maximumPoolSize(), debt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Провал публикации не должен ронять сервис: провижёр увидит худший случай и не выдаст
            // место преждевременно. Значение не запоминается, поэтому следующая попытка его повторит.
            lastError.set(e.toString());
            log.warn("не удалось опубликовать неосвобождённое сжатие {}: {} — место остаётся незанятым",
                    value, e.toString());
            return false;
        }
    }

    /** Отзыв аренды узла регистрации (best effort): при неудаче узел исчезнет сам по TTL. */
    private void revokeRegistration() {
        long lease = registrationLease.get();
        if (lease <= 0) {
            return;
        }
        Client c = client;
        if (c == null) {
            return;
        }
        try {
            c.getLeaseClient().revoke(lease)
                    .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("аренда узла регистрации отозвана (lease={})", lease);
            registrationLease.set(-1);
        } catch (Exception e) {
            log.warn("не удалось отозвать аренду регистрации (lease={}): {} — узел исчезнет по TTL",
                    lease, e.toString());
        }
    }

    /** Полный снимок пути + применение. */
    private long snapshot(Client c) throws Exception {
        GetResponse response = c.getKVClient()
                .get(pathBytes(), GetOption.newBuilder().isPrefix(true).build())
                .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);

        Map<String, String> fresh = new ConcurrentHashMap<>();
        for (KeyValue kv : response.getKvs()) {
            fresh.put(kv.getKey().toString(StandardCharsets.UTF_8), kv.getValue().toString(StandardCharsets.UTF_8));
        }
        keys.clear();
        keys.putAll(fresh);

        long revision = response.getHeader().getRevision();
        lastRevision.set(revision);
        connected.set(true);
        lastError.set(null);
        if (fresh.isEmpty() && emptyPrefixLogged.compareAndSet(false, true)) {
            log.warn("в пути конфигурации {} нет ни одного ключа — конфигурация не получена, "
                    + "трафик закрыт", path);
        }
        warnAboutUnknownKeys();
        apply("etcd-снапшот@" + revision);
        return revision;
    }

    private void watch(Client c, long fromRevision) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        ByteSequence prefix = pathBytes();

        try (Watch.Watcher watcher = c.getWatchClient().watch(prefix,
                WatchOption.newBuilder()
                        .withPrefix(prefix)
                        .withRevision(fromRevision)
                        .withPrevKV(true)
                        .build(),
                new Watch.Listener() {
                    @Override
                    public void onNext(WatchResponse response) {
                        onEvent(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        lastError.set(t.toString());
                        finished.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        log.info("etcd закрыл watch (revision ~{}), переподключаемся", fromRevision);
                        finished.countDown();
                    }
                })) {
            finished.await();
        }
    }

    private void onEvent(WatchResponse response) {
        lastRevision.set(response.getHeader().getRevision());
        List<WatchEvent> events = response.getEvents();
        if (events.isEmpty()) {
            return;
        }
        List<String> touched = new ArrayList<>();
        for (WatchEvent event : events) {
            String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
            String shortKey = EtcdKeys.shortKey(key, path);
            if (event.getEventType() == WatchEvent.EventType.PUT) {
                keys.put(key, event.getKeyValue().getValue().toString(StandardCharsets.UTF_8));
                touched.add(shortKey + "=put");
            } else {
                keys.remove(key);
                touched.add(shortKey + "=delete");
            }
        }
        lastEventAt.set(System.currentTimeMillis());
        warnAboutUnknownKeys();
        apply("etcd@" + lastRevision.get() + " [" + String.join(", ", touched) + "]");
    }

    private void apply(String reason) {
        try {
            EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, path);
            reportProblems(parsed.problems(), reason);

            // Инвариант: 0 из etcd недопустим. Пулом управляет провижер — размер 0 он присылает
            // «не присыланием ключей вовсе», а записанный вручную 0 (или занесённый оверайдом)
            // отклоняем целиком: пул остаётся на последних рабочих значениях, гейт открыт.
            Integer etcdMax = parsed.size().maximumPoolSize();
            if (etcdMax != null && etcdMax == 0) {
                ManagedPool.ApplyResult rejected = new ManagedPool.ApplyResult(
                        ManagedPool.Outcome.REJECTED,
                        List.of("rejected: maximumPoolSize=0 из etcd недопустим (пулом управляет провижёр)"),
                        null);
                lastOutcome.set(rejected.outcome());
                applyCount.incrementAndGet();
                counters.configRejected();
                lastError.set(String.join("; ", rejected.changes()));
                log.error("[{}] {}: пул остаётся на последних рабочих значениях",
                        reason, rejected.changes().get(0));
            } else {
                ManagedPool.ApplyResult result = pool.apply(parsed.size(), reason);
                lastOutcome.set(result.outcome());
                applyCount.incrementAndGet();
                if (result.outcome() == ManagedPool.Outcome.REJECTED) {
                    lastError.set(String.join("; ", result.changes()));
                }
            }
        } catch (InvalidSettingsException e) {
            lastError.set(e.getMessage());
            counters.configRejected();
            log.error("[{}] плохой конфиг из etcd: {}", reason, e.getMessage());
        } catch (RuntimeException e) {
            lastError.set(e.toString());
            log.error("[{}] не удалось применить конфиг из etcd", reason, e);
        } finally {
            updateReadiness(reason);
        }
    }

    /**
     * Гейт трафика: открывается, когда в наборе ключей появляется распознанный ключ; закрывается,
     * когда распознанных ключей не остаётся (провижер снял конфигурацию — инстанс, например,
     * потерял долю бюджета) и снова открывается при возврате ключей. Закрытие происходит только по
     * реальным событиям удаления/снимку при живом etcd: недоступное хранилище событий не шлёт и
     * гейт не трогает. Полученная, но отклонённая конфигурация гейт не удерживает — значение
     * получено, трафик открываем, а пул остаётся на последних рабочих значениях.
     */
    private void updateReadiness(String reason) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean hasRecognized = keys.keySet().stream()
                .anyMatch(key -> EtcdKeys.ALL.contains(EtcdKeys.shortKey(key, path)));
        if (hasRecognized) {
            if (trafficAllowed.compareAndSet(false, true)) {
                log.info("[{}] конфигурация по пути {} получена, открываем трафик", reason, path);
                eventPublisher.publishEvent(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
            }
            notReadyCause.set(null);
            notReadyReason.set(null);
            return;
        }
        // Распознанных ключей нет: конфигурация снята или ещё не приходила. Закрываем трафик,
        // если был открыт, и держим причину на виду.
        if (trafficAllowed.compareAndSet(true, false)) {
            log.warn("[{}] в пути {} не осталось распознанных ключей — закрываю трафик", reason, path);
            eventPublisher.publishEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        }
        refreshNotReadyReason();
    }

    private void refreshNotReadyReason() {
        if (!properties.isEnabled() || trafficAllowed.get()) {
            notReadyCause.set(null);
            notReadyReason.set(null);
            return;
        }
        NotReadyCause cause = connected.get() ? NotReadyCause.NO_CONFIG_KEYS : NotReadyCause.ETCD_UNAVAILABLE;
        notReadyCause.set(cause);
        notReadyReason.set(cause.describe(path, lastError.get()));
    }

    /** Мусор в значении не блокирует остальные ключи, но обязан быть виден. */
    private void reportProblems(Map<String, String> problems, String reason) {
        problems.forEach((key, problem) -> {
            if (reportedProblems.put(key, problem) == null) {
                // Считаем каждый ключ один раз: журнал проблем живёт всё время процесса, и счётчик
                // повторял бы ту же величину на каждом опросе при неизменившемся мусоре.
                counters.configUnreadable();
                log.error("[{}] ключ {} проигнорирован: {}", reason, key, problem);
            }
        });
    }

    private void warnAboutUnknownKeys() {
        for (String unknown : EtcdKeys.unknownKeys(keys, path)) {
            if (warnedUnknownKeys.add(unknown)) {
                log.warn("в etcd есть неизвестный ключ '{}' (путь '{}') — он игнорируется", unknown, path);
            }
        }
    }

    private ByteSequence pathBytes() {
        return ByteSequence.from(path, StandardCharsets.UTF_8);
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public EtcdStatus status() {
        Map<String, String> view = new TreeMap<>();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String key = entry.getKey();
            String shortKey = EtcdKeys.shortKey(key, path);
            // Ключа password в etcd больше не бывает: из хранилища читаются только размер и минимум,
            // учётные данные приходят локально. Маскирование оставлено — это защита от значения,
            // которое в etcd всё же появится, и стоит она одну строку.
            String val = MASKED_KEYS.contains(shortKey) ? "***" : entry.getValue();
            view.put(shortKey, val);
        }
        return new EtcdStatus(
                properties.isEnabled(),
                running.get() && connected.get(),
                properties.getEndpoints(),
                path,
                view,
                new TreeMap<>(reportedProblems),
                lastRevision.get(),
                lastEventAt.get(),
                lastError.get(),
                applyCount.get(),
                lastOutcome.get(),
                notReadyReason.get());
    }

    /**
     * Причина закрытой готовности.
     *
     * <p>Метки метрик берутся из перечисления, а текст для человека собирается из него же — иначе
     * показание и объяснение разошлись бы при первом же изменении формулировки.
     */
    public enum NotReadyCause {

        /** В пути конфигурации нет ни одного распознанного ключа. */
        NO_CONFIG_KEYS("no_config_keys"),
        /** Хранилище недоступно: событий удаления не приходило, конфигурация просто не пришла. */
        ETCD_UNAVAILABLE("etcd_unavailable");

        private final String metricName;

        NotReadyCause(String metricName) {
            this.metricName = metricName;
        }

        /** Значение метки причины в метриках. */
        public String metricName() {
            return metricName;
        }

        /** Человеческий текст причины: в теле 503 гейта и в деталях health. */
        public String describe(String path, String lastError) {
            return switch (this) {
                case NO_CONFIG_KEYS -> "конфигурация не получена: в пути " + path + " нет распознанных ключей";
                case ETCD_UNAVAILABLE -> "конфигурация не получена: etcd недоступен"
                        + (lastError != null ? " (" + lastError + ")" : "");
            };
        }
    }

    public record EtcdStatus(            boolean enabled,
            boolean connected,
            List<String> endpoints,
            /** путь ключей этого экземпляра; null, когда источник выключен */
            String path,
            Map<String, String> keys,
            /** ключи etcd, значение которых не удалось прочитать (ключ -> причина) */
            Map<String, String> problems,
            long revision,
            long lastEventEpochMs,
            String lastError,
            long applyCount,
            ManagedPool.Outcome lastOutcome,
            /** причина закрытой готовности; null, когда источник выключен или трафик открыт */
            String notReadyReason) {}
}