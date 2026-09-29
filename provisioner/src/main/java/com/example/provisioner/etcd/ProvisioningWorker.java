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
 * <p>Флот групп — глобальный: маркеры активности живут вне поддерева сервиса, под
 * {@code {root}/groups/{group}/active}. Инстансы неактивных групп сжимаются до резерва R
 * (сервисный ключ {@code inactiveMaxConnections}; 0 = холод, конфигурации нет вовсе), активные
 * делят остаток бюджета: активным достаётся {@code N − R×k}, сумма долей по сервису равна N.
 * Бюджет N и минимум m читаются из {@code activeMaxConnections}/{@code activeMinConnections};
 * {@code maximumPoolSize} — управляемый ключ, остальные ключи инстанса создаются по принципу
 * «ключа нет → put» и ручных правок не трогают. Маркеры поллятся отдельным потоком ~1с: события
 * {@code {root}/groups/} watch поддерева не видит, а при обрыве etcd воркер держит последнее
 * известное состояние флота.
 *
 * <p>Запускается и останавливается циклом выборов лидера ({@link ConfigProvisioner}): пока реплика
 * ведёт сервис, воркер крутит сверку + watch с revision+1 (никакого окна между снимком и
 * подпиской). Остановка (потеря лидерства, сбой аренды, завершение процесса) прерывает цикл;
 * события {@code hikari/*} игнорируются — петель нет. Ключи выборов лидера лежат вне
 * {@code {root}/services/}, поэтому в поддерево сервиса не попадают вовсе.
 */
final class ProvisioningWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningWorker.class);

    /** Период поллинга маркеров групп {@code {root}/groups/} — пересчёт по смене флага группы. */
    private static final long GROUP_POLL_MS = 1_000L;

    /** Снимок поддерева сервиса: чего в нём живого, что осталось от конфигурации, каков бюджет. */
    private static final class Tree {

        private final long revision;
        /** Ключ узла регистрации {@code .../instances/{i}/} → разобранный узел. */
        private final Map<String, InstanceKey.Node> nodes = new HashMap<>();
        /** Префиксы {@code .../instances/{i}/hikari/}, существующие в etcd. */
        private final Set<String> configPrefixes = new HashSet<>();
        /** Значения сервисных ключей бюджета по имени настройки. */
        private final Map<String, String> budget = new HashMap<>();
        /** Имена групп этого сервиса, помеченных inactive (маркер равен «false»). */
        private final Set<String> inactiveGroups = new HashSet<>();

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

    /** Последнее известное состояние маркеров групп (группа → активна); под {@link #lock}. */
    private Map<String, Boolean> lastFleetMarkers = new HashMap<>();
    /** Группы, к которым относятся узлы этого сервиса; обновляется в {@link #scan}. */
    private volatile Set<String> myGroups = Set.of();

    private volatile Thread thread;
    private volatile Thread pollThread;

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
        pollThread = Thread.ofPlatform()
                .daemon(true)
                .name("provision-fleet-" + service)
                .unstarted(this::fleetPollLoop);
        pollThread.start();
        log.info("воркер сервиса {} запущен на поддереве {}", service, subtreePrefix);
    }

    /** Остановка воркера: снимает флаг активности и прерывает оба цикла (watch закроется). */
    void stop() {
        active.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        Thread p = pollThread;
        if (p != null) {
            p.interrupt();
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
     * Полная сверка поддерева сервиса: обеспечение ключей бюджета и раздача долей. Возвращает
     * revision снимка — watch стартует с revision+1.
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

        // Маркеры активности групп лежат вне поддерева — читаем их отдельным снимком.
        Map<String, Boolean> fleet = readFleetMarkers(c);
        for (Map.Entry<String, Boolean> entry : fleet.entrySet()) {
            if (!entry.getValue()) {
                tree.inactiveGroups.add(entry.getKey());
            }
        }
        Set<String> groups = new HashSet<>();
        for (InstanceKey.Node node : tree.nodes.values()) {
            groups.add(node.group());
        }
        myGroups = groups;
        return tree;
    }

    /**
     * Снимок маркеров глобального флота групп {@code {root}/groups/}: группа → активна ли.
     * Отсутствующий маркер трактуется как активна; ключи префикса, отличные от
     * {@code {group}/active}, — мусор/опечатка, логируются один раз.
     */
    private Map<String, Boolean> readFleetMarkers(Client c) throws Exception {
        GetResponse resp = c.getKVClient()
                .get(ConfigProvisioner.bs(GroupFleetKey.groupsPrefix(owner.properties().getRoot())),
                        GetOption.newBuilder().isPrefix(true).build())
                .get(owner.callTimeoutMs(), TimeUnit.MILLISECONDS);
        Map<String, Boolean> markers = new HashMap<>();
        for (KeyValue kv : resp.getKvs()) {
            String key = kv.getKey().toString(StandardCharsets.UTF_8);
            String value = kv.getValue().toString(StandardCharsets.UTF_8);
            GroupFleetKey.Parsed parsed = GroupFleetKey.parse(owner.properties().getRoot(), key);
            if (parsed instanceof GroupFleetKey.Marker m) {
                markers.put(m.group(), GroupFleetKey.isActive(value));
            } else {
                warnUnknownKey(key);
            }
        }
        return markers;
    }

    /**
     * Поллинг маркеров флота: события {@code {root}/groups/} не видны watch поддерева, поэтому
     * флаги групп догоняются опросом. Смена затрагивает этот сервис — пересчёт; обрыв etcd тики
     * пропускает, состояние флота остаётся последним известным (смотрится в новом снимке после
     * восстановления).
     */
    private void fleetPollLoop() {
        long backoffMs = owner.properties().getRetryInitialBackoff().toMillis();
        while (active.get()) {
            try {
                Client c = owner.client();
                Map<String, Boolean> fresh = readFleetMarkers(c);
                synchronized (lock) {
                    Map<String, Boolean> prev = lastFleetMarkers;
                    lastFleetMarkers = fresh;
                    if (!fresh.equals(prev) && fleetChangedForUs(prev, fresh)) {
                        log.info("сервис {}: маркеры групп изменились, пересчитываю распределение", service);
                        recompute();
                    }
                }
                backoffMs = owner.properties().getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Обрыв etcd: пул/конфигурация живут на последнем состоянии, тики пропускаем.
                log.debug("воркер сервиса {}: маркеры групп не прочитаны ({}), повтор через {} мс",
                        service, e.toString(), backoffMs);
                if (!owner.sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, owner.properties().getRetryMaxBackoff().toMillis());
            }
            if (!owner.sleep(GROUP_POLL_MS)) {
                return;
            }
        }
    }

    /** Изменилось ли состояние маркеров групп, к которым относятся узлы этого сервиса. */
    private boolean fleetChangedForUs(Map<String, Boolean> prev, Map<String, Boolean> fresh) {
        Set<String> mine = myGroups;
        for (String group : mine) {
            if (!java.util.Objects.equals(prev.get(group), fresh.get(group))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Пересчёт сервиса: обеспечение сервисных ключей бюджета, партиционирование инстансов на
     * активные/неактивные по маркерам глобального флота, раздача долей (неактивным — ровно R,
     * активным — остаток бюджета) и очистка всего, что конфигурацией не обеспечено. Вызывается
     * под {@link #lock} — три источника событий (сверка, watch, поллер маркеров) не должны
     * считать состав одновременно.
     */
    private void apply(Client c, Tree tree) throws Exception {
        Integer budget = budgetValue(c, InstanceKey.ACTIVE_MAX_CONNECTIONS,
                owner.properties().getActiveMaxConnections(), tree);
        Integer min = budgetValue(c, InstanceKey.ACTIVE_MIN_CONNECTIONS,
                owner.properties().getActiveMinConnections(), tree);
        Integer reserve = budgetValue(c, InstanceKey.INACTIVE_MAX_CONNECTIONS,
                owner.properties().getInactiveMaxConnections(), tree);
        if (budget == null || min == null || reserve == null) {
            // Нечисловое значение сервисного ключа: fail-closed, конфигурацию не трогаем.
            log.warn("сервис {}: нечисловое значение ключа бюджета ({}/{}/{}), живы {} узлов: "
                    + "распределение не выполнено, конфигурация оставлена как есть", service,
                    InstanceKey.ACTIVE_MAX_CONNECTIONS, InstanceKey.ACTIVE_MIN_CONNECTIONS,
                    InstanceKey.INACTIVE_MAX_CONNECTIONS, tree.nodes.size());
            return;
        }

        // Глобальный флот: инстансы неактивных групп сжимаются до резерва R, остальные делят
        // остаток бюджета N − R×k по прежнему алгоритму.
        List<String> activeKeys = new ArrayList<>();
        List<String> inactiveKeys = new ArrayList<>();
        for (String nodeKey : tree.nodes.keySet()) {
            InstanceKey.Node node = tree.nodes.get(nodeKey);
            if (tree.inactiveGroups.contains(node.group())) {
                inactiveKeys.add(nodeKey);
            } else {
                activeKeys.add(nodeKey);
            }
        }
        Collections.sort(activeKeys);
        Collections.sort(inactiveKeys);

        int maxShare = owner.properties().getMaxShare();
        int k = inactiveKeys.size();
        long reserveTotal = (long) reserve * k;
        long activeBudget = budget - reserveTotal;
        if (reserve < 0 || reserve > maxShare) {
            // R вне {0} ∪ [1..maxShare]: fail-closed, конфигурацию не трогаем.
            log.warn("сервис {}: резерв неактивного флота R={} вне диапазона [0..{}] — "
                    + "распределение не выполнено, конфигурация оставлена как есть", service,
                    reserve, maxShare);
            return;
        }
        if (reserveTotal > budget) {
            log.warn("сервис {}: резерв неактивных R={} × k={} = {} превышает бюджет N={} — "
                    + "распределение не выполнено, конфигурация оставлена как есть", service,
                    reserve, k, reserveTotal, budget);
            return;
        }
        if (!activeKeys.isEmpty() && activeBudget < min) {
            log.warn("сервис {}: после резерва активным остаётся {} < минимума m={} (N={}, R={}, "
                    + "k={}) — распределение не выполнено, конфигурация оставлена как есть",
                    service, activeBudget, min, budget, reserve, k);
            return;
        }

        // Доли: неактивным ровно R (при R=0 доли нет вовсе — холод, конфигурация снимается
        // протиркой ниже), активным — остаток бюджета по прежнему алгоритму распределения.
        Map<String, Integer> shares = new HashMap<>();
        if (k > 0 && reserve > 0) {
            for (String nodeKey : inactiveKeys) {
                shares.put(nodeKey, reserve);
            }
        }
        int excess = 0;
        if (!activeKeys.isEmpty()) {
            PoolSizeDistribution.Result result = PoolSizeDistribution.distribute(
                    (int) activeBudget, min, maxShare, activeKeys);
            if (result.refused()) {
                // Fail-closed: опечатка в etcd не должна обнулить работающий сервис.
                log.warn("сервис {}: распределение активного бюджета не выполнено: {} (N={}, "
                                + "m={}, R={}, k={}) — конфигурация оставлена как есть", service,
                        result.refusal(), budget, min, reserve, k);
                return;
            }
            excess = result.excess();
            for (PoolSizeDistribution.Share share : result.shares()) {
                shares.put(share.nodeKey(), share.size());
            }
        }

        Map<String, String> shareByPrefix = new HashMap<>();
        for (Map.Entry<String, Integer> share : shares.entrySet()) {
            InstanceKey.Node node = tree.nodes.get(share.getKey());
            String prefix = InstanceKey.hikariPrefix(owner.properties().getRoot(),
                    node.service(), node.group(), node.instance());
            shareByPrefix.put(prefix, String.valueOf(share.getValue()));
        }

        int resizes = 0;
        for (Map.Entry<String, String> entry : shareByPrefix.entrySet()) {
            resizes += setPoolSize(c, entry.getKey(), entry.getValue());
            ensureKey(c, entry.getKey(), "connectionTimeoutMs",
                    String.valueOf(owner.properties().getConnectionTimeoutMs()));
        }

        // Избыток (доля не досталась), холод (R=0) и осиротевшие префиксы конфигурации — одинаково
        // не имеют права на ключи: без них инстанс не обслуживает трафик (503) и не держит
        // соединений.
        int cleaned = 0;
        Set<String> servedPrefixes = new HashSet<>(shareByPrefix.keySet());
        for (String prefix : tree.configPrefixes) {
            if (!servedPrefixes.contains(prefix)) {
                wipe(c, prefix);
                cleaned++;
            }
        }

        if (resizes > 0 || cleaned > 0 || excess > 0 || k > 0 || !activeKeys.isEmpty()) {
            log.info("пересчёт сервиса {}: N={}, m={}, R={}, активных {}, неактивных (k) {}, "
                            + "обслуживается {}, избыток {}, изменено размеров {}, очищено префиксов {}",
                    service, budget, min, reserve, activeKeys.size(), k, shares.size(),
                    excess, resizes, cleaned);
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