package com.example.provisioner.etcd;

import com.example.provisioner.config.ProvisionerProperties;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Провижинер конфигурации пула: держит инвариант «ключи конфигурации инстанса существуют
 * тогда и только тогда, когда существует его узел регистрации».
 *
 * <p>Появление узла {@code .../instances/{instance}} → в его путь {@code hikari/} добавляются
 * отсутствующие стартовые ключи (атомарный txn «ключа нет → put»; существующие — в том числе
 * ручные правки — никогда не перезаписываются). Исчезновение узла (штатная остановка, краш,
 * истечение TTL) → весь префикс {@code .../instances/{instance}/hikari/} удаляется.
 *
 * <p>Схема работы — как у конфиг-источника сервиса: снимок пути (get) + watch с revision+1,
 * чтобы между снимком и подпиской не было окна потерянного обновления. Любой обрыв
 * (compaction, рестарт etcd, потеря сети) приводит к новому снимку и полной сверке.
 * События ключей {@code hikari/*} (свои и чужие) игнорируются — петель нет.
 */
@Component
public class ConfigProvisioner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConfigProvisioner.class);

    private final ProvisionerProperties properties;

    /** Сериализация сверки и обработки watch-событий: две операции не должны пересекаться. */
    private final Object lock = new Object();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Client> clientRef = new AtomicReference<>();
    private final Set<String> warnedUnknownKeys = new HashSet<>();

    private final String servicesPrefix;

    private volatile Thread worker;

    public ConfigProvisioner(ProvisionerProperties properties) {
        this.properties = properties;
        this.servicesPrefix =
                InstanceKey.normalizedRoot(properties.getRoot()) + "/services/";
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

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("config-provisioner-watch")
                .unstarted(this::runLoop);
        worker.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
        Client c = clientRef.getAndSet(null);
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException e) {
                log.warn("не удалось закрыть etcd-клиент провижера: {}", e.toString());
            }
        }
    }

    /**
     * Цикл: сверка (снимок) → watch с revision+1. Ошибка или закрытый watch возвращают цикл
     * к новой сверке с экспоненциальным backoff'ом.
     */
    private void runLoop() {
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        while (running.get()) {
            try {
                Client c = client();
                long revision = reconcile(c);
                if (!running.get()) {
                    return;
                }
                watch(c, revision + 1);
                // watch завершился (compaction/reconnect) -> пересверяемся
                backoffMs = properties.getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("etcd недоступен/ошибка watch ({}), повтор через {} мс",
                        e.toString(), backoffMs);
                if (!sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Client client() {
        Client existing = clientRef.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (clientRef.get() == null) {
                Client c = Client.builder()
                        .endpoints(properties.getEndpoints().toArray(String[]::new))
                        .build();
                clientRef.set(c);
                log.info("провижинер стартует: endpoints={} services={}", properties.getEndpoints(),
                        servicesPrefix);
            }
            return clientRef.get();
        }
    }

    /**
     * Полная сверка: снимок дерева {@code {root}/services/}, обеспечение стартовых ключей всем
     * живым узлам и очистка осиротевших префиксов конфигурации. Возвращает revision снимка —
     * подписка стартует с revision+1 (никакого окна между снимком и watch).
     */
    private long reconcile(Client c) throws Exception {
        GetResponse resp = c.getKVClient()
                .get(bs(servicesPrefix), GetOption.newBuilder().isPrefix(true).build())
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);

        Map<String, InstanceKey.Node> nodes = new HashMap<>();
        Set<String> configPrefixes = new HashSet<>();
        for (KeyValue kv : resp.getKvs()) {
            String key = kv.getKey().toString(StandardCharsets.UTF_8);
            InstanceKey.Parsed parsed = InstanceKey.parse(properties.getRoot(), key);
            if (parsed instanceof InstanceKey.Node n) {
                nodes.put(key, n);
            } else if (parsed instanceof InstanceKey.Config cfg) {
                configPrefixes.add(InstanceKey.hikariPrefix(properties.getRoot(),
                        cfg.service(), cfg.group(), cfg.instance()));
            } else {
                warnUnknownKey(key);
            }
        }

        synchronized (lock) {
            Set<String> livePrefixes = new HashSet<>();
            for (InstanceKey.Node node : nodes.values()) {
                livePrefixes.add(InstanceKey.hikariPrefix(properties.getRoot(),
                        node.service(), node.group(), node.instance()));
                provision(c, node);
            }
            int orphans = 0;
            for (String orphan : configPrefixes) {
                if (!livePrefixes.contains(orphan)) {
                    wipe(c, orphan);
                    orphans++;
                }
            }
            log.info("сверка завершена: живых узлов {}, осиротевших префиксов {}",
                    nodes.size(), orphans);
        }
        return resp.getHeader().getRevision();
    }

    /** Обеспечение стартовых ключей живого инстанса (каждый — атомарно, «ключа нет → put»). */
    private void provision(Client c, InstanceKey.Node node) throws Exception {
        String prefix = InstanceKey.hikariPrefix(properties.getRoot(),
                node.service(), node.group(), node.instance());
        ensureKey(c, prefix, "maximumPoolSize", String.valueOf(properties.getMaximumPoolSize()));
        ensureKey(c, prefix, "connectionTimeoutMs",
                String.valueOf(properties.getConnectionTimeoutMs()));
    }

    private void ensureKey(Client c, String hikariPrefix, String name, String value)
            throws Exception {
        ByteSequence key = bs(hikariPrefix + name);
        TxnResponse resp = c.getKVClient().txn()
                .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.createRevision(0)))
                .Then(Op.put(key, bs(value), PutOption.DEFAULT))
                .commit()
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        if (resp.isSucceeded()) {
            log.info("провижининг: положен ключ {}={}", key.toString(StandardCharsets.UTF_8), value);
        } else {
            log.debug("провижининг: ключ {} уже существует, не трогаю",
                    key.toString(StandardCharsets.UTF_8));
        }
    }

    /** Удаление всего префикса конфигурации инстанса (включая ключи, добавленные вручную). */
    private void wipe(Client c, String hikariPrefix) throws Exception {
        DeleteResponse del = c.getKVClient()
                .delete(bs(hikariPrefix), DeleteOption.newBuilder().isPrefix(true).build())
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        long deleted = del.getDeleted();
        if (deleted > 0) {
            log.info("очистка: удалено {} ключей префикса {}", deleted, hikariPrefix);
        }
    }

    /** Долгоживущий watch: возвращается только на ошибке или закрытии стрима. */
    private void watch(Client c, long fromRevision) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        ByteSequence prefix = bs(servicesPrefix);
        try (Watch.Watcher watcher = c.getWatchClient().watch(prefix,
                WatchOption.newBuilder()
                        .withPrefix(prefix)
                        .withRevision(fromRevision)
                        .build(),
                new Watch.Listener() {
                    @Override
                    public void onNext(WatchResponse response) {
                        onEvent(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.warn("ошибка watch ({}), переподключаемся", t.toString());
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
        List<WatchEvent> events = response.getEvents();
        if (events.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (WatchEvent event : events) {
                handleEvent(event);
            }
        }
    }

    /** Одно событие: узел → провижин/очистка; ключ конфигурации → игнор; мусор → warn один раз. */
    private void handleEvent(WatchEvent event) {
        if (!running.get()) {
            // Остановка началась: не создаём новый клиент и не пишем в etcd на последних метрах.
            return;
        }
        String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
        InstanceKey.Parsed parsed = InstanceKey.parse(properties.getRoot(), key);
        try {
            if (parsed instanceof InstanceKey.Node n) {
                Client c = client();
                if (event.getEventType() == WatchEvent.EventType.PUT) {
                    log.info("узел {} появился: обеспечиваю конфигурацию", key);
                    provision(c, n);
                } else {
                    String prefix = InstanceKey.hikariPrefix(properties.getRoot(),
                            n.service(), n.group(), n.instance());
                    log.info("узел {} исчез: удаляю конфигурацию", key);
                    wipe(c, prefix);
                }
            } else if (parsed instanceof InstanceKey.Config) {
                // События конфигурации (свои записи и ручные правки) игнорируются — петель нет.
                log.debug("событие ключа конфигурации {} игнорируется", key);
            } else {
                warnUnknownKey(key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("не удалось обработать событие {} ключа {}: {} — догонит сверка",
                    event.getEventType(), key, e.toString());
        }
    }

    private void warnUnknownKey(String key) {
        synchronized (warnedUnknownKeys) {
            if (warnedUnknownKeys.add(key)) {
                log.warn("неизвестный ключ в дереве инстансов (опечатка?): {}", key);
            }
        }
    }

    private long callTimeoutMs() {
        return properties.getCallTimeout().toMillis();
    }

    private static ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }
}