package com.example.provisioner.etcd;

import com.example.provisioner.config.ProvisionerProperties;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Провижинер конфигурации пула с выбором лидера: в стенде два экземпляра, работает один.
 *
 * <p>Лидерство выбирается по каждому сервису узлами {@code {root}/provisioner/leader/{service}/}
 * (вне дерева {@code {root}/services/}): ключ узла — {@code <prefix>/<hex аренды>}, лидер —
 * ключ с наименьшим create_revision (старейший из живых). Набор сервисов реплика выводит сама из
 * узлов регистрации дерева {@code {root}/services/} — никакого списка в конфигурации.
 *
 * <p>Три собственных потока:
 * <ul>
 *   <li>{@code provisioner-lease} — одна аренда на реплику: grant(TTL) + keepalive с интервалом
 *       TTL/3 (как у регистрации инстансов). Сбой keepalive → аренда отзывается, воркеры
 *       останавливаются (лидер без аренды не действует), повтор с backoff 1→30с.</li>
 *   <li>{@code config-provisioner-election} — поллинг дерева сервисов ~1с: для каждого живого
 *       сервиса реплика обеспечивает свой ключ кампании (txn «ключа нет → put» с арендой — ранг
 *       не сбрасывается) и определяет лидера по наименьшему {@code create_revision} префикса;
 *       лидеру — воркер на поддерево сервиса, остальным — stop воркера; ушедшему из дерева
 *       сервису — resign своего ключа.</li>
 *   <li>{@code provision-worker-<service>} — сверка + watch поддерева сервиса (см.
 *       {@link ProvisioningWorker}), по одному на ведомый сервис.</li>
 * </ul>
 *
 * <p>Роли симметричны: рестарт реплики создаёт новый ключ с бо́льшим create_revision, и реплика
 * стартует фолловером; предпочтительного лидера нет. Записи провижинга идемпотентны
 * (txn «ключа нет → put»), поэтому краткое окно «двух писателей» при перехвате не ломает
 * конфигурацию.
 */
@Component
public class ConfigProvisioner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConfigProvisioner.class);

    /** Период поллинга дерева сервисов и кампаний выборов. */
    private static final long ELECTION_POLL_MS = 1_000L;

    private final ProvisionerProperties properties;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Client> clientRef = new AtomicReference<>();
    /** Аренда выборов этой реплики; -1 пока не получена. */
    private final AtomicLong leaseId = new AtomicLong(-1);

    /** Имя реплики — значение лидер-ключа; видно через {@code make leader}. */
    private final String replicaName;

    private final String servicesPrefix;
    private final String leaderRootPrefix;

    /** Воркеры по сервисам; доступ синхронизирован (стоп воркеров зовёт и lease-поток). */
    private final Map<String, ProvisioningWorker> workers = new HashMap<>();
    /** Текущее лидерство по сервисам — только для логов переходов (поток выборов). */
    private final Map<String, Boolean> leaderState = new HashMap<>();

    private volatile Thread leaseThread;
    private volatile Thread electionThread;

    public ConfigProvisioner(ProvisionerProperties properties) {
        this.properties = properties;
        this.replicaName = properties.resolveName();
        String root = InstanceKey.normalizedRoot(properties.getRoot());
        this.servicesPrefix = root + "/services/";
        this.leaderRootPrefix = root + "/provisioner/leader/";
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
        leaseThread = Thread.ofPlatform()
                .daemon(true)
                .name("provisioner-lease")
                .unstarted(this::leaseLoop);
        leaseThread.start();
        electionThread = Thread.ofPlatform()
                .daemon(true)
                .name("config-provisioner-election")
                .unstarted(this::electionLoop);
        electionThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread lt = leaseThread;
        if (lt != null) {
            lt.interrupt();
        }
        Thread et = electionThread;
        if (et != null) {
            et.interrupt();
        }
        stopAllWorkers();
        // Штатная остановка: аренда отзывается до закрытия клиента (ключи выборов исчезают сразу,
        // фолловер перехватывает без ожидания TTL).
        revokeLease();
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
     * Аренда выборов: grant(TTL) + keepalive с интервалом TTL/3. Сбой keepalive или grant →
     * лидерство слагается немедленно (воркеры остановлены, аренда отозвана), повтор с backoff'ом.
     */
    private void leaseLoop() {
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        while (running.get()) {
            try {
                Client c = client();
                long ttlSeconds = Math.max(1, properties.getLeaderTtl().getSeconds());
                long lease = c.getLeaseClient().grant(ttlSeconds)
                        .get(callTimeoutMs(), TimeUnit.MILLISECONDS).getID();
                leaseId.set(lease);
                log.info("реплика {}: аренда выборов получена lease={}, ttl={}с",
                        replicaName, lease, ttlSeconds);
                long keepaliveMs = ElectionTimings.keepaliveIntervalMillis(
                        properties.getLeaderTtl());
                while (running.get()) {
                    try {
                        c.getLeaseClient().keepAliveOnce(lease)
                                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        log.warn("реплика {}: keepalive аренды оборвался ({}), слагаю лидерство",
                                replicaName, e.toString());
                        onLeaseLost(lease);
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
                log.warn("реплика {}: аренда выборов не получена ({}), повтор через {} мс",
                        replicaName, e.toString(), backoffMs);
                leaseId.set(-1);
                stopAllWorkers();
                if (!sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    /** Потеря аренды (keepalive-сторож): воркеры остановлены, ключи узлов умрут по TTL. */
    private void onLeaseLost(long lease) {
        leaseId.compareAndSet(lease, -1);
        stopAllWorkers();
        try {
            client().getLeaseClient().revoke(lease).get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("аренда {} уже неактивна: {}", lease, e.toString());
        }
    }

    /**
     * Цикл выборов: снимок дерева {@code {root}/services/} → живой набор сервисов; каждому сервису
     * — кампания (лидер — старейший живой ключ), лидеру — воркер, остальным — stop воркера;
     * ушедшему из дерева сервису — resign своей кампании.
     */
    private void electionLoop() {
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        Set<String> known = new HashSet<>();
        while (running.get()) {
            try {
                Client c = client();
                long lease = leaseId.get();
                if (lease < 0) {
                    // Аренда ещё не получена lease-потоком — ждём следующего поллинга.
                    if (!sleep(ELECTION_POLL_MS)) {
                        return;
                    }
                    continue;
                }
                Set<String> services = scanServices(c);

                // Сервисы, полностью исчезнувшие из дерева: выбор лидера за них завершается.
                for (String gone : known) {
                    if (!services.contains(gone)) {
                        log.info("сервис {} исчез из дерева регистрации: завершаю выборы",
                                gone);
                        resignKey(c, gone, lease);
                        stopWorker(gone);
                    }
                }
                known = services;

                for (String service : services) {
                    if (electFor(c, service, lease)) {
                        startWorker(service);
                    } else {
                        stopWorker(service);
                    }
                }
                backoffMs = properties.getRetryInitialBackoff().toMillis();
                if (!sleep(ELECTION_POLL_MS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Не можем подтвердить лидерство — fail-closed: воркеры остановлены,
                // перевыборы после backoff'а (записи идемпотентны, потерь нет).
                log.warn("реплика {}: цикл выборов, ошибка ({}), повтор через {} мс",
                        replicaName, e.toString(), backoffMs);
                stopAllWorkers();
                if (!sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    /** Живой набор сервисов: сегмент {@code {service}} узлов регистрации (не ключей конфигурации). */
    private Set<String> scanServices(Client c) throws Exception {
        GetResponse resp = c.getKVClient()
                .get(bs(servicesPrefix), GetOption.newBuilder().isPrefix(true).build())
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        List<String> keys = new ArrayList<>(resp.getKvs().size());
        for (KeyValue kv : resp.getKvs()) {
            keys.add(kv.getKey().toString(StandardCharsets.UTF_8));
        }
        return InstanceKey.liveServices(properties.getRoot(), keys);
    }

    /**
     * Выборы за сервис: обеспечение своего ключа кампании и определение лидера по наименьшему
     * {@code create_revision} префикса {@code {root}/provisioner/leader/{service}/}. Никакого
     * блокирующего ожидания: вызов возвращается всегда, лидер решается сравнением аренд.
     */
    private boolean electFor(Client c, String service, long lease) throws Exception {
        ensureCandidate(c, service, lease);
        String prefix = leaderRootPrefix + service + "/";
        GetResponse resp = c.getKVClient()
                .get(bs(prefix), GetOption.newBuilder()
                        .withPrefix(bs(prefix))
                        .withSortField(GetOption.SortTarget.CREATE)
                        .withSortOrder(GetOption.SortOrder.ASCEND)
                        .withLimit(1)
                        .build())
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        List<KeyValue> kvs = resp.getKvs();
        boolean mine = !kvs.isEmpty() && kvs.get(0).getLease() == lease;
        Boolean prev = leaderState.get(service);
        if (mine && !Boolean.TRUE.equals(prev)) {
            log.info("реплика {} выиграла выборы сервиса {} (ключ {})", replicaName, service,
                    candidateKey(service, lease));
        } else if (!mine && Boolean.TRUE.equals(prev)) {
            log.info("реплика {} потеряла лидерство сервиса {} (ведёт lease {})", replicaName,
                    service, kvs.isEmpty() ? "нет" : kvs.get(0).getLease());
        }
        leaderState.put(service, mine);
        return mine;
    }

    /**
     * Свой ключ кампании: атомарный txn «ключа нет → put» с привязкой к аренде. Повторная кампания
     * существующий ключ не трогает — ранг (create_revision) не сбрасывается переполлингами.
     */
    private void ensureCandidate(Client c, String service, long lease) throws Exception {
        String key = candidateKey(service, lease);
        TxnResponse resp = c.getKVClient().txn()
                .If(new Cmp(bs(key), Cmp.Op.EQUAL, CmpTarget.createRevision(0)))
                .Then(Op.put(bs(key), bs(replicaName),
                        PutOption.newBuilder().withLeaseId(lease).build()))
                .commit()
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        if (resp.isSucceeded()) {
            log.info("реплика {} вступает в выборы сервиса {} (ключ {})", replicaName, service, key);
        }
    }

    /** Резинь за исчезнувший сервис: свой ключ кампании удаляется, аренда остаётся жить. */
    private void resignKey(Client c, String service, long lease) throws Exception {
        String key = candidateKey(service, lease);
        DeleteResponse del = c.getKVClient().delete(bs(key))
                .get(callTimeoutMs(), TimeUnit.MILLISECONDS);
        if (del.getDeleted() > 0) {
            log.info("выборы по сервису {} завершены: ключ {} удалён", service, key);
        }
    }

    /** Ключ кампании: {@code {root}/provisioner/leader/{service}/{hex аренды реплики}}. */
    private String candidateKey(String service, long lease) {
        return leaderRootPrefix + service + "/" + Long.toHexString(lease);
    }

    private void startWorker(String service) {
        synchronized (workers) {
            ProvisioningWorker w = workers.get(service);
            if (w == null || !w.isActive()) {
                w = new ProvisioningWorker(this, service);
                workers.put(service, w);
                w.start();
            }
        }
    }

    private void stopWorker(String service) {
        synchronized (workers) {
            ProvisioningWorker w = workers.remove(service);
            if (w != null) {
                w.stop();
            }
        }
    }

    private void stopAllWorkers() {
        synchronized (workers) {
            for (ProvisioningWorker w : workers.values()) {
                w.stop();
            }
            workers.clear();
        }
    }

    /** Отзыв аренды выборов (штатная остановка). Ключи узлов реплики исчезают сразу. */
    private void revokeLease() {
        long lease = leaseId.get();
        if (lease < 0) {
            return;
        }
        Client c = clientRef.get();
        if (c != null) {
            try {
                c.getLeaseClient().revoke(lease).get(callTimeoutMs(), TimeUnit.MILLISECONDS);
                log.info("реплика {}: аренда выборов отозвана lease={}", replicaName, lease);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("реплика {}: не удалось отозвать аренду ({}), ключи истекут по TTL",
                        replicaName, e.toString());
            }
        }
        leaseId.compareAndSet(lease, -1);
    }

    boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    Client client() {
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
                log.info("провижинер {} стартует: endpoints={} services={}", replicaName,
                        properties.getEndpoints(), servicesPrefix);
            }
            return clientRef.get();
        }
    }

    ProvisionerProperties properties() {
        return properties;
    }

    long callTimeoutMs() {
        return properties.getCallTimeout().toMillis();
    }

    static ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }
}