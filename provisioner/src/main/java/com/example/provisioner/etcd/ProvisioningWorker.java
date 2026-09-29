package com.example.provisioner.etcd;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Воркер провижининга одного сервиса: держит инвариант «ключи конфигурации инстанса существуют
 * тогда и только тогда, когда существует его узел» внутри поддерева
 * {@code {root}/services/{service}/}.
 *
 * <p>Запускается и останавливается циклом выборов лидера ({@link ConfigProvisioner}): пока реплика
 * ведёт сервис, воркер крутит сверку + watch с revision+1 (никакого окна между снимком и
 * подпиской). Остановка (потеря лидерства, сбой аренды, завершение процесса) прерывает цикл;
 * события {@code hikari/*} игнорируются — петель нет. Ключи выборов лидера лежат вне
 * {@code {root}/services/}, поэтому в поддерево сервиса не попадают вовсе.
 */
final class ProvisioningWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningWorker.class);

    private final ConfigProvisioner owner;
    private final String service;
    private final String subtreePrefix;

    /** Сериализация сверки и обработки watch-событий одного сервиса. */
    private final Object lock = new Object();
    private final AtomicBoolean active = new AtomicBoolean();
    private final Set<String> warnedUnknownKeys = new HashSet<>();

    private volatile Thread thread;

    ProvisioningWorker(ConfigProvisioner owner, String service) {
        this.owner = owner;
        this.service = service;
        this.subtreePrefix = InstanceKey.normalizedRoot(owner.properties().getRoot())
                + "/services/" + service + "/";
    }

    void start() {
        active.set(true);
        thread = Thread.ofPlatform()
                .daemon(true)
                .name("provision-worker-" + service)
                .unstarted(this);
        thread.start();
        log.info("воркер сервиса {} запущен на поддереве {}", service, subtreePrefix);
    }

    /** Остановка воркера: снимает флаг активности и прерывает цикл (watch закроется). */
    void stop() {
        active.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    boolean isActive() {
        return active.get();
    }

    @Override
    public void run() {
        long backoffMs = owner.properties().getRetryInitialBackoff().toMillis();
        while (active.get()) {
            try {
                Client c = owner.client();
                long revision = reconcile(c);
                if (!active.get()) {
                    return;
                }
                watch(c, revision + 1);
                // watch завершился (compaction/reconnect) -> пересверяемся
                backoffMs = owner.properties().getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("воркер сервиса {}: etcd недоступен/ошибка ({}), повтор через {} мс",
                        service, e.toString(), backoffMs);
                if (!owner.sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2,
                        owner.properties().getRetryMaxBackoff().toMillis());
            }
        }
    }

    /**
     * Полная сверка поддерева сервиса: обеспечение стартовых ключей всем живым узлам и очистка
     * осиротевших префиксов конфигурации. Возвращает revision снимка — watch стартует с revision+1.
     */
    private long reconcile(Client c) throws Exception {
        GetResponse resp = c.getKVClient()
                .get(ConfigProvisioner.bs(subtreePrefix),
                        GetOption.newBuilder().isPrefix(true).build())
                .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);

        Map<String, InstanceKey.Node> nodes = new HashMap<>();
        Set<String> configPrefixes = new HashSet<>();
        for (KeyValue kv : resp.getKvs()) {
            String key = kv.getKey().toString(StandardCharsets.UTF_8);
            InstanceKey.Parsed parsed = InstanceKey.parse(owner.properties().getRoot(), key);
            if (parsed instanceof InstanceKey.Node n) {
                nodes.put(key, n);
            } else if (parsed instanceof InstanceKey.Config cfg) {
                configPrefixes.add(InstanceKey.hikariPrefix(owner.properties().getRoot(),
                        cfg.service(), cfg.group(), cfg.instance()));
            } else {
                warnUnknownKey(key);
            }
        }

        synchronized (lock) {
            Set<String> livePrefixes = new HashSet<>();
            for (InstanceKey.Node node : nodes.values()) {
                livePrefixes.add(InstanceKey.hikariPrefix(owner.properties().getRoot(),
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
            log.info("сверка сервиса {} завершена: живых узлов {}, осиротевших префиксов {}",
                    service, nodes.size(), orphans);
        }
        return resp.getHeader().getRevision();
    }

    /** Обеспечение стартовых ключей живого инстанса (каждый — атомарно, «ключа нет → put»). */
    private void provision(Client c, InstanceKey.Node node) throws Exception {
        String prefix = InstanceKey.hikariPrefix(owner.properties().getRoot(),
                node.service(), node.group(), node.instance());
        ensureKey(c, prefix, "maximumPoolSize",
                String.valueOf(owner.properties().getMaximumPoolSize()));
        ensureKey(c, prefix, "connectionTimeoutMs",
                String.valueOf(owner.properties().getConnectionTimeoutMs()));
    }

    private void ensureKey(Client c, String hikariPrefix, String name, String value)
            throws Exception {
        ByteSequence key = ConfigProvisioner.bs(hikariPrefix + name);
        TxnResponse resp = c.getKVClient().txn()
                .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.createRevision(0)))
                .Then(Op.put(key, ConfigProvisioner.bs(value), PutOption.DEFAULT))
                .commit()
                .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);
        if (resp.isSucceeded()) {
            log.info("провижининг: положен ключ {}={}",
                    key.toString(StandardCharsets.UTF_8), value);
        } else {
            log.debug("провижининг: ключ {} уже существует, не трогаю",
                    key.toString(StandardCharsets.UTF_8));
        }
    }

    /** Удаление всего префикса конфигурации инстанса (включая ключи, добавленные вручную). */
    private void wipe(Client c, String hikariPrefix) throws Exception {
        DeleteResponse del = c.getKVClient()
                .delete(ConfigProvisioner.bs(hikariPrefix),
                        DeleteOption.newBuilder().isPrefix(true).build())
                .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);
        long deleted = del.getDeleted();
        if (deleted > 0) {
            log.info("очистка: удалено {} ключей префикса {}", deleted, hikariPrefix);
        }
    }

    /** Долгоживущий watch поддерева сервиса: возвращается на ошибке или закрытии стрима. */
    private void watch(Client c, long fromRevision) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        ByteSequence prefix = ConfigProvisioner.bs(subtreePrefix);
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
                        log.warn("ошибка watch сервиса {} ({}), переподключаемся",
                                service, t.toString());
                        finished.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        log.info("etcd закрыл watch сервиса {} (revision ~{}), переподключаемся",
                                service, fromRevision);
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
        if (!owner.isRunning() || !active.get()) {
            // Остановка началась или лидерство потеряно: в etcd больше не пишем.
            return;
        }
        String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
        InstanceKey.Parsed parsed = InstanceKey.parse(owner.properties().getRoot(), key);
        try {
            if (parsed instanceof InstanceKey.Node n) {
                Client c = owner.client();
                if (event.getEventType() == WatchEvent.EventType.PUT) {
                    log.info("сервис {}: узел {} появился: обеспечиваю конфигурацию",
                            service, key);
                    provision(c, n);
                } else {
                    String prefix = InstanceKey.hikariPrefix(owner.properties().getRoot(),
                            n.service(), n.group(), n.instance());
                    log.info("сервис {}: узел {} исчез: удаляю конфигурацию", service, key);
                    wipe(c, prefix);
                }
            } else if (parsed instanceof InstanceKey.Config) {
                // События конфигурации (свои записи и ручные правки) игнорируются — петель нет.
                log.debug("сервис {}: событие ключа конфигурации {} игнорируется", service, key);
            } else {
                warnUnknownKey(key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("сервис {}: не удалось обработать событие {} ключа {}: {} — догонит сверка",
                    service, event.getEventType(), key, e.toString());
        }
    }

    private void warnUnknownKey(String key) {
        synchronized (warnedUnknownKeys) {
            if (warnedUnknownKeys.add(key)) {
                log.warn("неизвестный ключ в дереве сервиса {} (опечатка?): {}", service, key);
            }
        }
    }
}