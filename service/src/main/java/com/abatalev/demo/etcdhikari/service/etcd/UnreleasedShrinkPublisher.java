package com.abatalev.demo.etcdhikari.service.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.abatalev.demo.etcdhikari.service.config.EtcdProperties;
import com.abatalev.demo.etcdhikari.service.otel.MechanismSpans;
import com.abatalev.demo.etcdhikari.service.pool.ManagedPool;

/**
 * Публикация неосвобождённого сжатия: сколько соединений инстанс держит сверх потолка.
 *
 * <p>Пока долг равен нулю, поток спит на смене конфигурации — ждать нечего, публикаций нет и не
 * должно быть. Как только применение конфигурации состоялось, берём долг у пула: он положителен
 * ровно тогда, когда сжатие ещё не освободило соединения, и обновляется по мере их возврата.
 * Терминальный ноль публикуем безусловно — на нём держится разблокировка роста.
 *
 * <p>Пока долг ненулевой, опрашиваем пул по таймеру: на пути сжатия активные соединения
 * возвращаются уже после применения конфигурации, и события в etcd об их возврате не приходят.
 * Публикация по событию, а не по расписанию: значение меняется вместе с числом соединений.
 *
 * <p>Запись идёт по аренде узла регистрации ({@link EtcdShared#registrationLease}), поэтому ключ
 * исчезает вместе с узлом и провизёр не считает его осиротевшим. Отдельного запроса от провизёра
 * не требуется. Цикл не трогает конфиг-воркер: применение и гейт живут в {@link EtcdConfigWorker}.
 */
final class UnreleasedShrinkPublisher {

    private static final Logger log = LoggerFactory.getLogger(UnreleasedShrinkPublisher.class);

    private final ManagedPool pool;
    private final EtcdProperties properties;
    private final MechanismSpans spans;
    private final EtcdShared shared;
    private final String unreleasedPath;

    UnreleasedShrinkPublisher(ManagedPool pool, EtcdProperties properties, MechanismSpans spans,
            EtcdShared shared, String unreleasedPath) {
        this.pool = pool;
        this.properties = properties;
        this.spans = spans;
        this.shared = shared;
        this.unreleasedPath = unreleasedPath;
    }

    /**
     * Цикл публикации: ждёт смены конфигурации, публикует долг, пока он ненулевой, затем снова
     * ждёт. Держится на общем флаге {@link EtcdShared#running} и умирает по прерыванию.
     */
    void loop() {
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

        while (shared.running.get()) {
            try {
                // Ждём смены конфигурации, пока публиковать нечего. Таймаут — страховка на случай,
                // если сигнал потеряется: повторная проверка дёшева, а молчание опасно.
                long previous = version;
                version = pool.awaitAppliedChange(version, idleMs);
                boolean ceilingChanged = version != previous;

                int debt = pool.runtime().unreleasedConnections();
                long lease = shared.registrationLease.get();
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
                if (!EtcdShared.sleep(pollMs)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                shared.lastError.set(e.toString());
                log.warn("публикация неосвобождённого сжатия не удалась: {}", e.toString());
                if (!EtcdShared.sleep(pollMs)) {
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
     * <p>Использует только {@link EtcdShared#currentClient()} — публикация не должна создавать
     * клиент вхолостую: пока конфигурации нет, ключ ещё некому было бы прочитать.
     *
     * @return true, если значение действительно записано
     */
    private boolean publishDebt(int value, int debt) {
        Client c = shared.currentClient();
        if (c == null || unreleasedPath == null) {
            return false;
        }
        long lease = shared.registrationLease.get();
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
            shared.lastError.set(e.toString());
            log.warn("не удалось опубликовать неосвобождённое сжатие {}: {} — место остаётся незанятым",
                    value, e.toString());
            return false;
        }
    }
}