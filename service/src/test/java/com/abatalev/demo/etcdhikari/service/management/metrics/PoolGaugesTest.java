package com.abatalev.demo.etcdhikari.service.management.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.abatalev.demo.etcdhikari.service.management.config.DbProperties;
import com.abatalev.demo.etcdhikari.service.management.config.EtcdProperties;
import com.abatalev.demo.etcdhikari.service.management.etcd.EtcdPoolConfigSource;
import com.abatalev.demo.etcdhikari.service.management.otel.MechanismSpans;
import com.abatalev.demo.etcdhikari.service.management.pool.ManagedPool;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Ряды наблюдения на пуле без конфигурации — то самое состояние, в котором инстанс стартует и в
 * которое попадает, когда провизёр снял долю.
 *
 * <p>Метрики обязаны отдавать нули, а не бросать: сбор идёт на насыщенном пуле, и упавший ряд
 * уронил бы весь снимок. Соединение при этом не берётся вовсе, поэтому тест обходится без БД.
 */
class PoolGaugesTest {

    private static final String SECRET = "parol-ne-v-metrikah";

    private ManagedPool poolWithoutConfig(DbProperties db, PoolCounters counters) {
        return new ManagedPool(db, false, Duration.ZERO, counters, MechanismSpans.NOOP);
    }

    /** Пароль задаём везде: он не должен просочиться ни в один ряд ни в одном состоянии пула. */
    private DbProperties dbWithSecret() {
        DbProperties db = new DbProperties();
        db.setPassword(SECRET);
        return db;
    }

    private EtcdPoolConfigSource disabledSource(ManagedPool pool, PoolCounters counters) {
        EtcdProperties etcd = new EtcdProperties();
        etcd.setEnabled(false);
        return new EtcdPoolConfigSource(pool, etcd, event -> {}, counters, MechanismSpans.NOOP);
    }

    @Test
    @DisplayName("без пула ряды отдают нули, а закрытый пул помечен единицей")
    void gaugesAreZeroWithoutPool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PoolCounters counters = new PoolCounters(registry);
        ManagedPool pool = poolWithoutConfig(dbWithSecret(), counters);
        new PoolGauges(registry, pool, disabledSource(pool, counters));

        assertThat(registry.get("pool.connections.open").gauge().value()).isZero();
        assertThat(registry.get("pool.connections.busy").gauge().value()).isZero();
        assertThat(registry.get("pool.connections.awaiting").gauge().value()).isZero();
        assertThat(registry.get("pool.unreleased_shrink").gauge().value()).isZero();
        assertThat(registry.get("pool.config.maximum_pool_size").gauge().value()).isZero();
        // Пула нет вовсе — это и есть «пул закрыт» (1), а не «пул работает».
        assertThat(registry.get("pool.closing").gauge().value()).isEqualTo(1d);
    }

    @Test
    @DisplayName("снятие всех рядов не бросает: чтение должно быть безопасным")
    void readingAllGaugesDoesNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PoolCounters counters = new PoolCounters(registry);
        ManagedPool pool = poolWithoutConfig(dbWithSecret(), counters);
        new PoolGauges(registry, pool, disabledSource(pool, counters));

        assertThatCode(() -> registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("pool."))
                .forEach(m -> m.measure().iterator().forEachRemaining(measurement -> {
                    })))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("состояние конфигурации и причина готовности — закрытые наборы меток")
    void closedSetsOfLabelValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PoolCounters counters = new PoolCounters(registry);
        ManagedPool pool = poolWithoutConfig(dbWithSecret(), counters);
        new PoolGauges(registry, pool, disabledSource(pool, counters));

        // Источник выключен: конфигурация не «отсутствует», а просто не из etcd.
        assertThat(registry.get("pool.config.state").tag("state", "none").gauge().value()).isEqualTo(1d);
        assertThat(registry.get("pool.config.state").tag("state", "applied").gauge().value()).isZero();
        assertThat(registry.get("pool.not_ready_reason").tag("reason", "none").gauge().value()).isEqualTo(1d);
        assertThat(registry.get("pool.traffic_gate_open").gauge().value()).isEqualTo(1d);
        assertThat(registry.get("pool.etcd.enabled").gauge().value()).isZero();

        // Наборы меток не растут со временем: иначе по ним нельзя построить график.
        assertThat(registry.getMeters()).filteredOn(m -> m.getId().getName().equals("pool.config.state")).hasSize(3);
        assertThat(registry.getMeters()).filteredOn(m -> m.getId().getName().equals("pool.not_ready_reason")).hasSize(3);
    }

    @Test
    @DisplayName("пароль доступа к базе не попадает ни в имена, ни в значения метрик")
    void passwordNeverLeaksIntoMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PoolCounters counters = new PoolCounters(registry);
        ManagedPool pool = poolWithoutConfig(dbWithSecret(), counters);
        new PoolGauges(registry, pool, disabledSource(pool, counters));

        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getName()).doesNotContain(SECRET);
            assertThat(meter.getId().getTags()).noneMatch(t -> t.getValue().contains(SECRET));
        }
    }

    @Test
    @DisplayName("ряд переживает сборку мусора: состояние гейджа держится Micrometer'ом слабо")
    void gaugesSurviveGarbageCollection() {
        // Именно этот дефект выдаёт себя на стенде: сборщик отдаёт NaN по всем рядам пула, пока
        // процесс жив, и ни один запрос к API этого не показывает. Поэтому проверка идёт через
        // настоящий текстовый формат prometheus и через принудительный сбор мусора.
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PoolCounters counters = new PoolCounters(registry);
        ManagedPool pool = poolWithoutConfig(dbWithSecret(), counters);
        new PoolGauges(registry, pool, disabledSource(pool, counters));

        String scrape = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            System.gc();
            scrape = registry.scrape();
            if (!scrape.contains("NaN")) {
                break;
            }
        }

        assertThat(scrape).doesNotContain("NaN");
        assertThat(scrape).contains("pool_connections_open");
        // Ноль пула — это ноль, а не «нет значения»: ряд обязан остаться в снимке.
        assertThat(scrape).containsPattern("pool_closing(\\{[^}]*})? 1.0");
    }
}
