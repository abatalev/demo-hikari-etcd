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
import java.util.ArrayList;
import java.util.Collections;
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
 * тогда и только тогда, когда существует его узел и узел получил долю сервисного бюджета» внутри
 * поддерева {@code {root}/services/{service}/}.
 *
 * <p>Бюджет N читается из сервисного ключа {@code {root}/services/{service}/maxConnections} и
 * делится равномерно между живыми инстансами, сумма долей равна N. Минимум m
 * ({@code .../minConnections}) ограничивает состав снизу: инстанс без доли остаётся без
 * конфигурации и потому не обслуживает трафик. {@code maximumPoolSize} — управляемый ключ,
 * остальные ключи инстанса создаются по принципу «ключа нет → put» и ручных правок не трогают.
 *
 * <p>Запускается и останавливается циклом выборов лидера ({@link ConfigProvisioner}): пока реплика
 * ведёт сервис, воркер крутит сверку + watch с revision+1 (никакого окна между снимком и
 * подпиской). Остановка (потеря лидерства, сбой аренды, завершение процесса) прерывает цикл;
 * события {@code hikari/*} игнорируются — петель нет. Ключи выборов лидера лежат вне
 * {@code {root}/services/}, поэтому в поддерево сервиса не попадают вовсе.
 */
final class ProvisioningWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningWorker.class);

    /** Снимок поддерева сервиса: чего в нём живого, что осталось от конфигурации, каков бюджет. */
    private static final class Tree {

        private final long revision;
        /** Ключ узла регистрации {@code .../instances/{i}/} → разобранный узел. */
        private final Map<String, InstanceKey.Node> nodes = new HashMap<>();
        /** Префиксы {@code .../instances/{i}/hikari/}, существующие в etcd. */
        private final Set<String> configPrefixes = new HashSet<>();
        /** Значения сервисных ключей бюджета по имени настройки. */
        private final Map<String, String> budget = new HashMap<>();

        private Tree(long revision) {
            this.revision = revision;
        }
    }

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
     * Полная сверка поддерева сервиса: обеспечение ключей бюджета, раздача долей всем живым узлам
     * и очистка всего, что конфигурацией не обеспечено. Возвращает revision снимка — watch
     * стартует с revision+1.
     */
    private long reconcile(Client c) throws Exception {
        Tree snapshot = scan(c);
        synchronized (lock) {
            apply(c, snapshot);
        }
        return snapshot.revision;
    }

    /**
     * Снимок поддерева сервиса: живые узлы, существующие префиксы конфигурации и значения ключей
     * бюджета. Ничего не пишет — чтение безопасно для событий, которые сами же и породили.
     */
    private Tree scan(Client c) throws Exception {
        GetResponse resp = c.getKVClient()
                .get(ConfigProvisioner.bs(subtreePrefix),
                        GetOption.newBuilder().isPrefix(true).build())
                .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);

        Tree tree = new Tree(resp.getHeader().getRevision());
        for (KeyValue kv : resp.getKvs()) {
            String key = kv.getKey().toString(StandardCharsets.UTF_8);
            String value = kv.getValue().toString(StandardCharsets.UTF_8);
            InstanceKey.Parsed parsed = InstanceKey.parse(owner.properties().getRoot(), key);
            if (parsed instanceof InstanceKey.Node n) {
                tree.nodes.put(key, n);
            } else if (parsed instanceof InstanceKey.Config cfg) {
                tree.configPrefixes.add(InstanceKey.hikariPrefix(owner.properties().getRoot(),
                        cfg.service(), cfg.group(), cfg.instance()));
            } else if (parsed instanceof InstanceKey.ServiceSetting s) {
                tree.budget.put(s.setting(), value);
            } else {
                warnUnknownKey(key);
            }
        }
        return tree;
    }

    /**
     * Пересчёт сервиса: обеспечение сервисных ключей бюджета, раздача долей и очистка всего, что
     * конфигурацией не обеспечено. Вызывается под {@link #lock} — два источника событий (сверка и
     * watch) не должны считать состав одновременно.
     */
    private void apply(Client c, Tree tree) throws Exception {
        Integer budget = budgetValue(c, InstanceKey.MAX_CONNECTIONS,
                owner.properties().getMaxConnections(), tree);
        Integer min = budgetValue(c, InstanceKey.MIN_CONNECTIONS,
                owner.properties().getMinConnections(), tree);
        if (budget == null || min == null) {
            // Нечисловое значение сервисного ключа: fail-closed, конфигурацию не трогаем.
            log.warn("сервис {}: нечисловое значение ключа бюджета ({}/{}), живы {} узлов: "
                    + "распределение не выполнено, конфигурация оставлена как есть", service,
                    InstanceKey.MAX_CONNECTIONS, InstanceKey.MIN_CONNECTIONS, tree.nodes.size());
            return;
        }

        List<String> nodeKeys = new ArrayList<>(tree.nodes.keySet());
        Collections.sort(nodeKeys);
        PoolSizeDistribution.Result result = PoolSizeDistribution.distribute(budget, min,
                owner.properties().getMaxShare(), nodeKeys);
        if (result.refused()) {
            // Fail-closed: опечатка в etcd не должна обнулить работающий сервис.
            log.warn("сервис {}: распределение бюджета не выполнено: {} (N={}, m={}, n={}) — "
                            + "конфигурация оставлена как есть", service, result.refusal(), budget,
                    min, tree.nodes.size());
            return;
        }

        Map<String, String> shareByPrefix = new HashMap<>();
        for (PoolSizeDistribution.Share share : result.shares()) {
            InstanceKey.Node node = tree.nodes.get(share.nodeKey());
            String prefix = InstanceKey.hikariPrefix(owner.properties().getRoot(),
                    node.service(), node.group(), node.instance());
            shareByPrefix.put(prefix, String.valueOf(share.size()));
        }

        int resizes = 0;
        for (Map.Entry<String, String> entry : shareByPrefix.entrySet()) {
            resizes += setPoolSize(c, entry.getKey(), entry.getValue());
            ensureKey(c, entry.getKey(), "connectionTimeoutMs",
                    String.valueOf(owner.properties().getConnectionTimeoutMs()));
        }

        // Избыток (доля не досталась) и осиротевшие префиксы конфигурации — одинаково не имеют
        // права на ключи: без них инстанс не обслуживает трафик (503) и не держит соединений.
        int cleaned = 0;
        Set<String> servedPrefixes = new HashSet<>(shareByPrefix.keySet());
        for (String prefix : tree.configPrefixes) {
            if (!servedPrefixes.contains(prefix)) {
                wipe(c, prefix);
                cleaned++;
            }
        }

        if (resizes > 0 || cleaned > 0 || result.excess() > 0) {
            log.info("пересчёт сервиса {}: бюджет {}, минимум {}, живых узлов {}, обслуживается {}, "
                            + "избыток {}, изменено размеров {}, очищено префиксов {}",
                    service, budget, min, tree.nodes.size(), result.shares().size(),
                    result.excess(), resizes, cleaned);
        }
    }

    /**
     * Обеспечение сервисного ключа бюджета: значения оператора не трогаем, отсутствующий ключ
     * создаём дефолтом провижера (txn «ключа нет → put»). Ключ переживает опустение сервиса —
     * при удалении последнего узла он не удаляется, иначе rolling update с окном без живых узлов
     * сбросил бы бюджет оператора на дефолт.
     *
     * @return действующее значение ключа, либо {@code null} — значение есть, но не число
     */
    private Integer budgetValue(Client c, String setting, int defaultValue, Tree tree)
            throws Exception {
        String raw = tree.budget.get(setting);
        if (raw == null) {
            String key = InstanceKey.serviceSettingKey(owner.properties().getRoot(), service, setting);
            TxnResponse resp = c.getKVClient().txn()
                    .If(new Cmp(ConfigProvisioner.bs(key), Cmp.Op.EQUAL,
                            CmpTarget.createRevision(0)))
                    .Then(Op.put(ConfigProvisioner.bs(key), ConfigProvisioner.bs(
                            String.valueOf(defaultValue)), PutOption.DEFAULT))
                    .commit()
                    .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);
            if (resp.isSucceeded()) {
                log.info("провижининг: создан ключ бюджета {}={}", key, defaultValue);
            }
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("сервис {}: значение ключа {} не является числом: \"{}\"", service, setting, raw);
            return null;
        }
    }

    /**
     * Приведение {@code maximumPoolSize} к доле бюджета. Ключ управляемый: перезаписывается при
     * расхождении, при равенстве записи нет вовсе. Условие инвертировано: если значение УЖЕ равно
     * доле, обходная ветка пуста и записи нет; в противном случае (значение отличается ИЛИ ключа
     * нет — сравнение значения с несуществующим ключом в etcd никогда не проходит, поэтому
     * NOT_EQUAL тут не подходил) выполняется ветка Else и ключ пишется/создаётся. Один поход в
     * etcd, никакой гонки с ручной правкой оператора; две реплики в окне перехвата лидерства пишут
     * одно и то же значение — записи идемпотентны.
     *
     * @return 1, если значение изменено или ключ создан, 0 — уже было равно доле
     */
    private int setPoolSize(Client c, String hikariPrefix, String size) throws Exception {
        ByteSequence key = ConfigProvisioner.bs(hikariPrefix + "maximumPoolSize");
        ByteSequence target = ConfigProvisioner.bs(size);
        TxnResponse resp = c.getKVClient().txn()
                .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.value(target)))
                .Else(Op.put(key, target, PutOption.DEFAULT))
                .commit()
                .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);
        if (!resp.isSucceeded()) {
            log.info("пересчёт: {} maximumPoolSize={}", hikariPrefix, size);
            return 1;
        }
        log.debug("пересчёт: {} maximumPoolSize уже {}", hikariPrefix, size);
        return 0;
    }

    /** Обеспечение стартового ключа инстанса (каждый — атомарно, «ключа нет → put»). */
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

    /** Удаление всего префикса конфигурации инстанса: ключи провижинера и добавленные вручную. */
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

    /**
     * Одно событие. Узел или сервисный ключ бюджета меняют состав сервиса — значит, доли
     * пересчитываются целиком, иначе сумма долей разъехалась бы с бюджетом. Ключ конфигурации
     * игнорируем: свои записи и ручные правки оператора не должны порождать действия (иначе
     * провижёр сам себя зациклит перезаписью размера). Мусор — warn один раз.
     */
    private void handleEvent(WatchEvent event) {
        if (!owner.isRunning() || !active.get()) {
            // Остановка началась или лидерство потеряно: в etcd больше не пишем.
            return;
        }
        String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
        InstanceKey.Parsed parsed = InstanceKey.parse(owner.properties().getRoot(), key);
        try {
            if (parsed instanceof InstanceKey.Node) {
                log.info("сервис {}: узел {} {}: пересчитываю распределение бюджета", service, key,
                        event.getEventType() == WatchEvent.EventType.PUT ? "появился" : "исчез");
                recompute();
            } else if (parsed instanceof InstanceKey.ServiceSetting s) {
                log.info("сервис {}: ключ бюджета {} {}: пересчитываю распределение", service, key,
                        event.getEventType() == WatchEvent.EventType.PUT ? "изменён" : "удалён");
                recompute();
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

    /**
     * Пересчёт по событию: снимок поддерева и полная раздача долей. Снимок нужен потому, что
     * событие изменило только один узел, а доли считаются по всему составу сервиса.
     */
    private void recompute() throws Exception {
        Client c = owner.client();
        Tree tree = scan(c);
        apply(c, tree);
    }

    private void warnUnknownKey(String key) {
        synchronized (warnedUnknownKeys) {
            if (warnedUnknownKeys.add(key)) {
                log.warn("неизвестный ключ в дереве сервиса {} (опечатка?): {}", service, key);
            }
        }
    }
}