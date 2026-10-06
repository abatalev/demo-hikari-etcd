package com.abatalev.demo.etcdhikari.service.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.abatalev.demo.etcdhikari.service.config.EtcdProperties;

/**
 * Регистрация инстанса в etcd: узел {@code .../instances/{instance}} на аренде (TTL + keepalive).
 *
 * <p>Цикл: grant(ttl) → put(узел, пустое значение, lease) → keepalive каждые ttl/3. Любая ошибка
 * (обрыв etcd, истёкшая аренда) возвращает цикл к перерегистрации с backoff — узел
 * восстанавливается, как только хранилище снова отвечает. Штатный стоп аренду отзывает в
 * {@link #revokeRegistration()} (фасад вызывает его после дренажа пула); здесь при прерывании
 * просто выходим.
 *
 * <p>Узел лежит на уровень выше {@code hikari/}, поэтому конфиг-воркер его не видит — на
 * готовность и гейт регистрация не влияет. Падение процесса убирает узел по истечении TTL.
 */
final class InstanceRegistration {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistration.class);

    private final EtcdProperties properties;
    private final EtcdShared shared;
    private final String nodePath;

    /**
     * Путь конфигурации инстанса; нужен только для строки лога при создании клиента: клиент общий,
     * и какой бы цикл ни создал его первым, лог показывает путь конфигурации, как и было.
     */
    private final String path;

    InstanceRegistration(EtcdProperties properties, EtcdShared shared, String nodePath, String path) {
        this.properties = properties;
        this.shared = shared;
        this.nodePath = nodePath;
        this.path = path;
    }

    /** Цикл регистрации: grant → put → keepalive, на обрыве — перерегистрация с бэкоффом. */
    void loop() {
        long ttlSeconds = properties.getRegistrationTtl().getSeconds();
        long keepaliveMs = Math.max(1, ttlSeconds / 3) * 1000L;
        long backoffMs = properties.getRetryInitialBackoff().toMillis();
        while (shared.running.get()) {
            try {
                Client c = shared.clientOrCreate(properties.getEndpoints(), path);
                Lease leaseClient = c.getLeaseClient();
                long lease = leaseClient.grant(ttlSeconds)
                        .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .getID();
                shared.registrationLease.set(lease);
                c.getKVClient()
                        .put(ByteSequence.from(nodePath, StandardCharsets.UTF_8), ByteSequence.EMPTY,
                                PutOption.newBuilder().withLeaseId(lease).build())
                        .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
                log.info("инстанс зарегистрирован: узел {} (lease={}, ttl={}s)", nodePath, lease, ttlSeconds);
                while (shared.running.get()) {
                    try {
                        leaseClient.keepAliveOnce(lease)
                                .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        log.warn("keepalive узла регистрации оборвался ({}), перерегистрируюсь",
                                e.toString());
                        if (!EtcdShared.sleep(backoffMs)) {
                            return;
                        }
                        break;
                    }
                    if (!EtcdShared.sleep(keepaliveMs)) {
                        return;
                    }
                }
                backoffMs = properties.getRetryInitialBackoff().toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("регистрация не удалась ({}), повтор через {} мс", e.toString(), backoffMs);
                if (!EtcdShared.sleep(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, properties.getRetryMaxBackoff().toMillis());
            }
        }
    }

    /**
     * Отзыв аренды узла регистрации (best effort): при неудаче узел исчезнет сам по TTL.
     *
     * <p>Использует только {@link EtcdShared#currentClient()} — отзыв не должен создавать клиент
     * вхолостую. Вызывается фасадом до и после остановки потока регистрации: второй заход ловит
     * аренду, которую поток успел выдать, но ещё не довёл до публикации.
     */
    void revokeRegistration() {
        long lease = shared.registrationLease.get();
        if (lease <= 0) {
            return;
        }
        Client c = shared.currentClient();
        if (c == null) {
            return;
        }
        try {
            c.getLeaseClient().revoke(lease)
                    .get(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("аренда узла регистрации отозвана (lease={})", lease);
            shared.registrationLease.set(-1);
        } catch (Exception e) {
            log.warn("не удалось отозвать аренду регистрации (lease={}): {} — узел исчезнет по TTL",
                    lease, e.toString());
        }
    }
}