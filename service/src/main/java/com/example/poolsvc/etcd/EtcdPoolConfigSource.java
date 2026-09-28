package com.example.poolsvc.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.example.poolsvc.config.DbProperties;
import com.example.poolsvc.config.EtcdProperties;
import com.example.poolsvc.pool.HikariSettings;
import com.example.poolsvc.pool.InvalidSettingsException;
import com.example.poolsvc.pool.ManagedPool;

/**
 * Единственный источник конфигурации пула — etcd.
 *
 * <p>Схема работы: снимок префикса (get) + watch с revision+1. Разрыв между снимком и watch'ем
 * невозможен, потому что watch стартует с той же ревизии. Любой обрыв (compaction, рестарт etcd,
 * потеря сети) приводит к новому снимку — просто и всегда корректно для префикса из ~10 ключей.
 *
 * <p>Сервис стартует на локальных дефолтах и не падает, если etcd недоступен: watch в фоне
 * докарачивается с backoff'ом, а в /api/pool видно, что конфиг приехал не из etcd.
 */
@Component
public class EtcdPoolConfigSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EtcdPoolConfigSource.class);

    private final ManagedPool pool;
    private final HikariSettings defaults;
    private final EtcdProperties properties;
    private final Map<String, String> keys = new ConcurrentHashMap<>();
    private final Map<String, String> reportedProblems = new ConcurrentHashMap<>();
    private final Set<String> warnedUnknownKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicLong lastRevision = new AtomicLong();
    private final AtomicLong lastEventAt = new AtomicLong();
    private final AtomicLong applyCount = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<ManagedPool.Outcome> lastOutcome = new AtomicReference<>();

    private volatile Client client;
    private volatile Thread watcherThread;

    public EtcdPoolConfigSource(ManagedPool pool, DbProperties dbProperties, EtcdProperties properties) {
        this.pool = pool;
        this.defaults = dbProperties.toSettings();
        this.properties = properties;
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
        watcherThread = Thread.ofPlatform()
                .daemon(true)
                .name("etcd-config-watch")
                .unstarted(this::watchLoop);
        watcherThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = watcherThread;
        if (thread != null) {
            thread.interrupt();
        }
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
                log.info("etcd watch стартует: endpoints={} prefix={}", properties.getEndpoints(), prefix());
            }
            return client;
        }
    }

    /** Полный снимок префикса + применение. */
    private long snapshot(Client c) throws Exception {
        GetResponse response = c.getKVClient()
                .get(prefixBytes(), GetOption.newBuilder().isPrefix(true).build())
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
        warnAboutUnknownKeys();
        apply("etcd-снапшот@" + revision);
        return revision;
    }

    private void watch(Client c, long fromRevision) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        ByteSequence prefix = prefixBytes();

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
            String shortKey = EtcdKeys.shortKey(key, prefix());
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
            EtcdKeys.Parsed parsed = EtcdKeys.parse(keys, prefix());
            reportProblems(parsed.problems(), reason);

            HikariSettings desired = parsed.settings().resolve(defaults);
            ManagedPool.ApplyResult result = pool.apply(desired, reason);
            lastOutcome.set(result.outcome());
            applyCount.incrementAndGet();
            if (result.outcome() == ManagedPool.Outcome.REJECTED) {
                lastError.set(String.join("; ", result.changes()));
            }
        } catch (InvalidSettingsException e) {
            lastError.set(e.getMessage());
            log.error("[{}] плохой конфиг из etcd: {}", reason, e.getMessage());
        } catch (RuntimeException e) {
            lastError.set(e.toString());
            log.error("[{}] не удалось применить конфиг из etcd", reason, e);
        }
    }

    /** Мусор в значении не блокирует остальные ключи, но обязан быть виден. */
    private void reportProblems(Map<String, String> problems, String reason) {
        problems.forEach((key, problem) -> {
            if (reportedProblems.put(key, problem) == null) {
                log.error("[{}] ключ {} проигнорирован: {}", reason, key, problem);
            }
        });
    }

    private void warnAboutUnknownKeys() {
        for (String unknown : EtcdKeys.unknownKeys(keys, prefix())) {
            if (warnedUnknownKeys.add(unknown)) {
                log.warn("в etcd есть неизвестный ключ '{}' (префикс '{}') — он игнорируется", unknown, prefix());
            }
        }
    }

    private String prefix() {
        String p = properties.getPrefix();
        return p.endsWith("/") ? p : p + "/";
    }

    private ByteSequence prefixBytes() {
        return ByteSequence.from(prefix(), StandardCharsets.UTF_8);
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
            String shortKey = EtcdKeys.shortKey(key, prefix());
            String val = EtcdKeys.PASSWORD.equals(shortKey) ? "***" : entry.getValue();
            view.put(shortKey, val);
        }
        return new EtcdStatus(
                properties.isEnabled(),
                running.get() && connected.get(),
                properties.getEndpoints(),
                prefix(),
                view,
                new TreeMap<>(reportedProblems),
                lastRevision.get(),
                lastEventAt.get(),
                lastError.get(),
                applyCount.get(),
                lastOutcome.get());
    }

    public record EtcdStatus(
            boolean enabled,
            boolean connected,
            List<String> endpoints,
            String prefix,
            Map<String, String> keys,
            /** ключи etcd, значение которых не удалось прочитать (ключ -> причина) */
            Map<String, String> problems,
            long revision,
            long lastEventEpochMs,
            String lastError,
            long applyCount,
            ManagedPool.Outcome lastOutcome) {}
}
