package com.example.provisioner.config;

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

    /** Стартовое значение maximumPoolSize для вновь зарегистрированных инстансов. */
    private int maximumPoolSize = 10;

    /** Стартовое значение connectionTimeoutMs для вновь зарегистрированных инстансов. */
    private int connectionTimeoutMs = 3000;

    private Duration callTimeout = Duration.ofSeconds(5);
    private Duration retryInitialBackoff = Duration.ofSeconds(1);
    private Duration retryMaxBackoff = Duration.ofSeconds(30);

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

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
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
}