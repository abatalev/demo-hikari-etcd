package com.example.provisioner.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки провижера конфигурации пула.
 *
 * <p>Провижер наблюдает за деревом {@code {root}/services/} и держит инвариант «ключи
 * конфигурации инстанса существуют тогда и только тогда, когда существует его узел
 * регистрации»: появление узла добавляет отсутствующие стартовые ключи, исчезновение узла
 * удаляет весь префикс {@code .../{instance}/hikari/}.
 */
@ConfigurationProperties(prefix = "provision")
public class ProvisionerProperties {

    private List<String> endpoints = List.of("http://localhost:2379");

    /** Корень ключей (дефолт «/config»); сегменты пути такие же, как у сервиса. */
    private String root = "/config";

    /**
     * Бюджет соединений сервиса по умолчанию (ключ {@code {root}/services/{service}/maxConnections},
     * создаётся put-if-absent при появлении первого узла сервиса). Провижер делит его равномерно
     * между живыми инстансами, сумма долей равна бюджету.
     */
    private int maxConnections = 100;

    /**
     * Минимальная доля инстанса по умолчанию (ключ {@code .../minConnections}). Инстанс
     * обслуживает трафик, только если его доля не меньше этого значения; если бюджета не хватает,
     * часть инстансов остаётся без конфигурации.
     */
    private int minConnections = 1;

    /**
     * Верхняя граница доли инстанса. Намеренно зеркалит {@code HikariSettings.POOL_SIZE_MAX} у
     * сервиса: доля выше границы усекается, иначе сервис отклонил бы конфиг целиком.
     */
    private int maxShare = 200;

    /** Стартовое значение connectionTimeoutMs для вновь зарегистрированных инстансов. */
    private int connectionTimeoutMs = 3000;

    private Duration callTimeout = Duration.ofSeconds(5);
    private Duration retryInitialBackoff = Duration.ofSeconds(1);
    private Duration retryMaxBackoff = Duration.ofSeconds(30);

    /**
     * Имя реплики провизора: значение лидер-ключа выборов и пометка в логах.
     * Пустое значение → фолбэк на hostname (уникален в compose и в Kubernetes).
     */
    private String name = "";

    /** TTL аренды выборов лидера: после потери keepalive лидерство уходит за это время. */
    private Duration leaderTtl = Duration.ofSeconds(10);

    public List<String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMinConnections() {
        return minConnections;
    }

    public void setMinConnections(int minConnections) {
        this.minConnections = minConnections;
    }

    public int getMaxShare() {
        return maxShare;
    }

    public void setMaxShare(int maxShare) {
        this.maxShare = maxShare;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = retryInitialBackoff;
    }

    public Duration getRetryMaxBackoff() {
        return retryMaxBackoff;
    }

    public void setRetryMaxBackoff(Duration retryMaxBackoff) {
        this.retryMaxBackoff = retryMaxBackoff;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Duration getLeaderTtl() {
        return leaderTtl;
    }

    public void setLeaderTtl(Duration leaderTtl) {
        this.leaderTtl = leaderTtl;
    }

    /** Имя реплики: явное {@code PROV_NAME}, иначе hostname, иначе «provisioner». */
    public String resolveName() {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "provisioner";
        }
    }
}