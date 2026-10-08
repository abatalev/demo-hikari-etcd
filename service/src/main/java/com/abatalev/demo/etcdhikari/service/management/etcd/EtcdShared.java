package com.abatalev.demo.etcdhikari.service.management.etcd;

import io.etcd.jetcd.Client;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Общее состояние трёх циклов источника конфигурации: флаг работы, аренда узла регистрации,
 * клиент etcd и последняя ошибка диагностики.
 *
 * <p>Состояние разделяют {@link EtcdConfigWorker}, {@link InstanceRegistration} и
 * {@link UnreleasedShrinkPublisher}: аренда выдаётся регистрацией и читается публикацией (ключ
 * публикации держится на аренде узла), клиент открывается воркером и регистрацией, отзывается
 * фасадом. Один общий объект — вместо того чтобы прокидывать атомики между коллабораторами
 * по одному.
 *
 * <p>Клиент доступен двумя способами: {@link #clientOrCreate(List, String)} создаёт его при
 * первом обращении (воркер и регистрация), {@link #currentClient()} только читает и никогда не
 * создаёт — публикация долга и отзыв аренды намеренно не поднимают соединение вхолостую.
 */
final class EtcdShared {

    private static final Logger log = LoggerFactory.getLogger(EtcdShared.class);

    /** Флаг работы всех трёх циклов; старт и остановку ими управляет фасад. */
    final AtomicBoolean running = new AtomicBoolean();

    /** Номер текущей аренды узла регистрации; -1, когда аренды нет. */
    final AtomicLong registrationLease = new AtomicLong(-1);

    /** Последняя ошибка диагностики; одно поле на все циклы, как было до разделения. */
    final AtomicReference<String> lastError = new AtomicReference<>();

    private volatile Client client;

    /** Клиент etcd; создаётся при первом обращении, закрывается фасадом в {@code stop()}. */
    Client clientOrCreate(List<String> endpoints, String path) {
        Client existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = Client.builder()
                        .endpoints(endpoints.toArray(String[]::new))
                        .build();
                log.info("etcd watch стартует: endpoints={} path={}", endpoints, path);
            }
            return client;
        }
    }

    /** Текущий клиент без создания; null — клиент ещё не создан. */
    Client currentClient() {
        return client;
    }

    /** Пауза с обработкой прерывания: false — поток прерван, нужно выйти из цикла. */
    static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}