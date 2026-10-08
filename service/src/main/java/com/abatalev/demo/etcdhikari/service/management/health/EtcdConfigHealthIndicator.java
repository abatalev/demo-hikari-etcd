package com.abatalev.demo.etcdhikari.service.management.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.abatalev.demo.etcdhikari.service.management.etcd.EtcdPoolConfigSource;

/**
 * Показывает состояние гейта трафика в readiness: причина закрытой готовности видна в
 * /actuator/health без обращения к API. Живость (liveness) от etcd не зависит — этот
 * индикатор включается только в группу readiness.
 */
@Component("poolEtcd")
public class EtcdConfigHealthIndicator implements HealthIndicator {

    private final EtcdPoolConfigSource source;

    public EtcdConfigHealthIndicator(EtcdPoolConfigSource source) {
        this.source = source;
    }

    @Override
    public Health health() {
        if (!source.isEnabled()) {
            return Health.up().withDetail("config-source", "выключен").build();
        }
        if (source.isTrafficAllowed()) {
            return Health.up()
                    .withDetail("config-source", "конфигурация получена")
                    .withDetail("path", source.path())
                    .build();
        }
        String reason = source.notReadyReason();
        return Health.down()
                .withDetail("config-source", "трафик закрыт: конфигурация не получена")
                .withDetail("path", source.path())
                .withDetail("reason", reason == null ? "причина не установлена" : reason)
                .build();
    }
}