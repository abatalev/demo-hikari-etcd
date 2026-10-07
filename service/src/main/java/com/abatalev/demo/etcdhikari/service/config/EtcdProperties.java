package com.abatalev.demo.etcdhikari.service.config;

import java.time.Duration;
import java.util.Collections;
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

    /**
     * Квант промежуточной публикации неосвобождённого сжатия, в соединениях.
     *
     * <p>0 (по умолчанию) — не фильтровать: публикуется каждое изменение величины. Ненулевое
     * значение пропускает промежуточные значения, не дотягивающие до кванта; на терминальный
     * ноль фильтр не действует, иначе место освобождения не дошло бы до провижёра.
     */
    private int publishQuantum = 0;

    /** Период опроса долга, пока он ненулевой (освобождение идёт постепенно). */
    private Duration publishPollInterval = Duration.ofSeconds(1);

    /** Сколько ждать смены конфигурации, пока долг равен нулю (страховка от пропуска сигнала). */
    private Duration publishIdleInterval = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getEndpoints() {
        // Неизменяемая обёртка: чтение клиентом не должно влиять на конфигурацию процесса.
        return Collections.unmodifiableList(endpoints);
    }

    public void setEndpoints(List<String> endpoints) {
        // Копия: Spring-биндинг не должен разделять список с вызывающим кодом.
        this.endpoints = List.copyOf(endpoints);
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

    public int getPublishQuantum() {
        return publishQuantum;
    }

    public void setPublishQuantum(int publishQuantum) {
        this.publishQuantum = publishQuantum;
    }

    public Duration getPublishPollInterval() {
        return publishPollInterval;
    }

    public void setPublishPollInterval(Duration publishPollInterval) {
        this.publishPollInterval = publishPollInterval;
    }

    public Duration getPublishIdleInterval() {
        return publishIdleInterval;
    }

    public void setPublishIdleInterval(Duration publishIdleInterval) {
        this.publishIdleInterval = publishIdleInterval;
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