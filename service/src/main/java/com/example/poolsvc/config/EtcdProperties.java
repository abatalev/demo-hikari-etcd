package com.example.poolsvc.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pool.etcd")
public class EtcdProperties {

    /** Выключатель: false = сервис живёт только на локальных дефолтах, etcd не опрашивается. */
    private boolean enabled = true;

    private List<String> endpoints = List.of("http://localhost:2379");

    /** Корень ключей конфигурации (дефолт «/config»); сегменты пути см. ниже. */
    private String root = "/config";

    /** Имя сервиса — первый сегмент пути. */
    private String service;

    /** Группа (namespace) — второй сегмент пути. */
    private String group;

    /** Имя инстанса (pod name) — третий сегмент пути, обязателен при enabled=true. */
    private String instance;

    private Duration callTimeout = Duration.ofSeconds(5);
    private Duration retryInitialBackoff = Duration.ofSeconds(1);
    private Duration retryMaxBackoff = Duration.ofSeconds(30);

    /** TTL аренды узла регистрации инстанса; keepalive идёт каждые TTL/3 (дефолт 15с → 5с). */
    private Duration registrationTtl = Duration.ofSeconds(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
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

    public Duration getRegistrationTtl() {
        return registrationTtl;
    }

    public void setRegistrationTtl(Duration registrationTtl) {
        this.registrationTtl = registrationTtl;
    }
}