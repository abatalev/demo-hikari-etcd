package com.abatalev.demo.etcdhikari.service.etcd;

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
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import com.abatalev.demo.etcdhikari.service.config.EtcdProperties;
import com.abatalev.demo.etcdhikari.service.metrics.PoolCounters;
import com.abatalev.demo.etcdhikari.service.otel.MechanismSpans;
import com.abatalev.demo.etcdhikari.service.pool.InvalidSettingsException;
import com.abatalev.demo.etcdhikari.service.pool.ManagedPool;
import com.abatalev.demo.etcdhikari.service.etcd.EtcdPoolConfigSource.EtcdStatus;
import com.abatalev.demo.etcdhikari.service.etcd.EtcdPoolConfigSource.NotReadyCause;

/**
 * Конфиг-воркер инстанса: снимок пути + watch и применение конфигурации к пулу.
 *
 * <p>Единственный источник конфигурации пула — etcd. Схема работы: снимок пути (get) + watch
 * с revision+1. Разрыв между снимком и watch'ем невозможен, потому что watch стартует с той же
 * ревизии. Любой обрыв (compaction, рестарт etcd, потеря сети) приводит к новому снимку — просто
 * и всегда корректно для пути из ~10 ключей.
 *
 * <p>Гейт трафика: пока конфигурация не получена (в пути нет ни одного распознанного ключа),
 * сервис остаётся запущенным, но не готов принимать трафик — готовность (readiness) закрыта,
 * живость (liveness) от etcd не зависит. Гейт открывается первым распознанным ключом и
 * закрывается, когда распознанных ключей не остаётся (провижер снял конфигурацию — инстанс,
 * например, потерял долю бюджета), после чего снова открывается при возврате ключей. Закрытие
 * происходит только по реальным событиям удаления при живом etcd: недоступность хранилища ни
 * трафик, ни готовность не останавливает — в этом случае события не приходят вовсе.
 *
 * <p>Регистрация инстанса и публикация неосвобождённого сжатия живут в отдельных классах
 * ({@link InstanceRegistration}, {@link UnreleasedShrinkPublisher}) и в этот префикс не попадают.
 */
final class EtcdConfigWorker {

    private static final Logger log = LoggerFactory.getLogger(EtcdConfigWorker.class);

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
    private final MechanismSpans spans;
    private final EtcdShared shared;
    private final String path;

    private final Map<String, String> keys = new ConcurrentHashMap<>();
    private final Map<String, String> reportedProblems = new ConcurrentHashMap<>();
    private final Set<String> warnedUnknownKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean trafficAllowed = new AtomicBoolean();
    private final AtomicLong lastRevision = new AtomicLong();
    private final AtomicLong lastEventAt = new AtomicLong();
    private final AtomicLong applyCount = new AtomicLong();
    private final AtomicReference<String> notReadyReason = new AtomicReference<>();
    private final AtomicReference<NotReadyCause> notReadyCause = new AtomicReference<>();
    private final AtomicReference<ManagedPool.Outcome> lastOutcome = new AtomicReference<>();
    private final AtomicBoolean emptyPrefixLogged = new AtomicBoolean();

    EtcdConfigWorker(ManagedPool pool, EtcdProperties properties,
            ApplicationEventPublisher eventPublisher, PoolCounters counters, MechanismSpans spans,
            EtcdShared shared, String path) {
        this.pool = pool;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.counters = counters;
        this.spans = spans;
        this.shared = shared;
        this.path = path;
    }

    /** Старт воркера: гейт закрыт до первого распознанного ключа. */
    void start() {
        // Стартуем с закрытой готовностью: до первого распознанного ключа трафик не принимаем.
        eventPublisher.publishEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
    }

    /** Жива ли подписка: цикл watch запущен, даже если последний снимок не удался. */
    boolean isWatchActive() {
        return shared.running.get();
    }

    /** Отвечает ли etcd: последний снимок получен, обрыв сбрасывает признак. */
    boolean isConnected() {
        return connected.get();
    }

    /** Открыт ли трафик: false, пока конфигурация не получена (при включённом источнике). */
    boolean isTrafficAllowed() {
        return trafficAllowed.get();
    }

    /** Причина закрытой готовности; null, когда источник выключен или трафик открыт. */
    String notReadyReason() {
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
    NotReadyCause notReadyCause() {
        return notReadyCause.get();
    }

    /** Цикл подписки: снимок, watch с revision+1, на обрыве — переснапшот с бэкоффом. */
    void loop() {
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        while (shared.running.get()) {
            try {
                Client c = shared.clientOrCreate(properties.getEndpoints(), path);
                long revision = snapshot(c);
                watch(c, revision + 1);
                // watch завершился штатно (compaction/reconnect с нашей стороны) -> переснапшотим
                backoffMs = properties.getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                connected.set(false);
                shared.lastError.set(e.toString());
                log.warn("etcd недоступен/ошибка watch ({}), повтор через {} мс", e.toString(), backoffMs);
                refreshNotReadyReason();
                if (!EtcdShared.sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    /** Полный снимок пути + применение. */
    private long snapshot(Client c) throws Exception {
        GetResponse response = c.getKVClient()
                .get(pathBytes(), GetOption.builder().isPrefix(true).build())
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
        shared.lastError.set(null);
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
                WatchOption.builder()
                        .isPrefix(true)
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
                        shared.lastError.set(t.toString());
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
                shared.lastError.set(String.join("; ", rejected.changes()));
                log.error("[{}] {}: пул остаётся на последних рабочих значениях",
                        reason, rejected.changes().get(0));
            } else {
                ManagedPool.ApplyResult result = pool.apply(parsed.size(), reason);
                lastOutcome.set(result.outcome());
                applyCount.incrementAndGet();
                if (result.outcome() == ManagedPool.Outcome.REJECTED) {
                    shared.lastError.set(String.join("; ", result.changes()));
                }
            }
        } catch (InvalidSettingsException e) {
            shared.lastError.set(e.getMessage());
            counters.configRejected();
            log.error("[{}] плохой конфиг из etcd: {}", reason, e.getMessage());
        } catch (RuntimeException e) {
            shared.lastError.set(e.toString());
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
        notReadyReason.set(cause.describe(path, shared.lastError.get()));
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

    /** Снимок состояния источника для API и метрик. */
    EtcdStatus status() {
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
                shared.running.get() && connected.get(),
                properties.getEndpoints(),
                path,
                view,
                new TreeMap<>(reportedProblems),
                lastRevision.get(),
                lastEventAt.get(),
                shared.lastError.get(),
                applyCount.get(),
                lastOutcome.get(),
                notReadyReason.get());
    }
}