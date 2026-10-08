package com.abatalev.demo.etcdhikari.service.management.etcd;

import io.etcd.jetcd.Client;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.abatalev.demo.etcdhikari.service.management.config.EtcdProperties;
import com.abatalev.demo.etcdhikari.service.management.metrics.PoolCounters;
import com.abatalev.demo.etcdhikari.service.management.otel.MechanismSpans;
import com.abatalev.demo.etcdhikari.service.management.pool.ManagedPool;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Фасад источника конфигурации пула: координирует три независимых цикла на общем состоянии.
 *
 * <p>Единственный источник конфигурации пула — etcd. Путь конфигурации
 * ({@code {root}/services/{service}/groups/{group}/instances/{instance}/hikari/}) собирается из
 * сегментов в конструкторе через {@link EtcdKeyPath}; незаполненные сегменты при включённом
 * источнике прекращают запуск с внятной причиной.
 *
 * <p>Работа разнесена по трём классам того же пакета на общем состоянии {@link EtcdShared}:
 *
 * <ul>
 *   <li>{@link EtcdConfigWorker} — снимок пути + watch с revision+1 и применение конфигурации,
 *       гейт трафика и признаки источника;</li>
 *   <li>{@link InstanceRegistration} — узел {@code .../instances/{instance}} на аренде
 *       (TTL + keepalive), на готовность и гейт не влияет;</li>
 *   <li>{@link UnreleasedShrinkPublisher} — публикация неосвобождённого сжатия по аренде узла.</li>
 * </ul>
 *
 * <p>Фасад остаётся {@code SmartLifecycle}: держит потоки трёх циклов, ленивый клиент etcd
 * (которым владеет {@link EtcdShared}), последовательность остановки (дренаж → revoke → join)
 * и публичный API для потребителей — он не менялся при разделении.
 */
@Component
public class EtcdPoolConfigSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EtcdPoolConfigSource.class);

    private final ManagedPool pool;
    private final EtcdProperties properties;
    private final MechanismSpans spans;
    /** Путь ключей этого экземпляра; null, когда источник выключен. */
    private final String path;

    private final EtcdShared shared = new EtcdShared();
    private final EtcdConfigWorker worker;
    private final InstanceRegistration registration;
    private final UnreleasedShrinkPublisher publisher;

    private volatile Thread watcherThread;
    private volatile Thread registrationThread;
    private volatile Thread publicationThread;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring-бины (ManagedPool, EtcdProperties) разделяются контейнером по дизайну")
    public EtcdPoolConfigSource(ManagedPool pool, EtcdProperties properties,
            ApplicationEventPublisher eventPublisher, PoolCounters counters, MechanismSpans spans) {
        this.pool = pool;
        this.properties = properties;
        this.spans = spans == null ? MechanismSpans.NOOP : spans;
        String nodePath;
        String unreleasedPath;
        if (properties.isEnabled()) {
            this.path = EtcdKeyPath.build(properties.getRoot(), properties.getService(),
                    properties.getGroup(), properties.getInstance());
            nodePath = EtcdKeyPath.nodePath(properties.getRoot(), properties.getService(),
                    properties.getGroup(), properties.getInstance());
            unreleasedPath = EtcdKeyPath.unreleasedConnectionsPath(properties.getRoot(),
                    properties.getService(), properties.getGroup(), properties.getInstance());
        } else {
            this.path = null;
            nodePath = null;
            unreleasedPath = null;
        }
        this.worker = new EtcdConfigWorker(pool, properties, eventPublisher, counters, shared, path);
        this.registration = new InstanceRegistration(properties, shared, nodePath, path);
        this.publisher = new UnreleasedShrinkPublisher(pool, properties, this.spans, shared,
                unreleasedPath);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String path() {
        return path;
    }

    /** Причина закрытой готовности; null, когда источник выключен или трафик открыт. */
    public String notReadyReason() {
        return worker.notReadyReason();
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
        return worker.notReadyCause();
    }

    /** Жива ли подписка: цикл watch запущен, даже если последний снимок не удался. */
    public boolean isWatchActive() {
        return worker.isWatchActive();
    }

    /** Отвечает ли etcd: последний снимок получен, обрыв сбрасывает признак. */
    public boolean isConnected() {
        return worker.isConnected();
    }

    /** Открыт ли трафик: false, пока конфигурация не получена (при включённом источнике). */
    public boolean isTrafficAllowed() {
        return !properties.isEnabled() || worker.isTrafficAllowed();
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("etcd-конфиг выключен (pool.etcd.enabled=false), работаем на локальных дефолтах");
            return;
        }
        if (!shared.running.compareAndSet(false, true)) {
            return;
        }
        // Стартуем с закрытой готовностью: до первого распознанного ключа трафик не принимаем.
        worker.start();
        watcherThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-config-watch")
                .unstarted(worker::loop);
        watcherThread.start();
        // Регистрация — отдельный поток: keepalive не должен блокировать конфиг-watch.
        registrationThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-registration")
                .unstarted(registration::loop);
        registrationThread.start();
        // Публикация неосвобождённого сжатия — тоже отдельный поток: она ждёт смены конфигурации
        // и опрашивает пул, пока место не освободится, и не должна блокировать watch.
        publicationThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-drain-publication")
                .unstarted(publisher::loop);
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
        if (!shared.running.compareAndSet(true, false)) {
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
        registration.revokeRegistration();
        if (r != null) {
            try {
                r.join(properties.getCallTimeout().toMillis() + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        registration.revokeRegistration();
        Client c = shared.currentClient();
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
        return shared.running.get();
    }

    /** Снимок состояния источника для API и метрик; строится конфиг-воркером. */
    public EtcdStatus status() {
        return worker.status();
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
    public record EtcdStatus(
            boolean enabled,
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
            String notReadyReason) {
        @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
                justification = "record-компоненты — контракт данных; явный канонический конструктор — рабочая "
                        + "точка подавления (класс-аннотация на record даёт US_USELESS_SUPPRESSION_ON_CLASS, spotbugs 4.9.3)")
        public EtcdStatus {
        }

        @Override
        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "record-компоненты — контракт данных; явный акцессор — рабочая точка подавления "
                        + "(класс-аннотация на record даёт US_USELESS_SUPPRESSION_ON_CLASS, spotbugs 4.9.3)")
        public List<String> endpoints() {
            return endpoints;
        }

        @Override
        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "record-компоненты — контракт данных; явный акцессор — рабочая точка подавления "
                        + "(класс-аннотация на record даёт US_USELESS_SUPPRESSION_ON_CLASS, spotbugs 4.9.3)")
        public Map<String, String> keys() {
            return keys;
        }

        @Override
        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "record-компоненты — контракт данных; явный акцессор — рабочая точка подавления "
                        + "(класс-аннотация на record даёт US_USELESS_SUPPRESSION_ON_CLASS, spotbugs 4.9.3)")
        public Map<String, String> problems() {
            return problems;
        }
    }
}