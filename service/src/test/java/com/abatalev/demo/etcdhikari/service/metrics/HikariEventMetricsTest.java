package com.abatalev.demo.etcdhikari.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Приборы событий пула — единственная часть наблюдения, которую считает сам HikariCP.
 *
 * <p>Покрыта регистрация и честность счётчиков; сам пул и его насыщение требуют БД и проверяются
 * на стенде.
 */
class HikariEventMetricsTest {

    @Test
    @DisplayName("события выдачи, удержания, открытия и таймаута попадают в свои приборы")
    void eventsAreRecorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HikariEventMetrics metrics = new HikariEventMetrics(registry);

        var tracker = metrics.create("pool", null);
        tracker.recordConnectionAcquiredNanos(1_000_000L);
        tracker.recordConnectionUsageMillis(250L);
        tracker.recordConnectionCreatedMillis(42L);
        tracker.recordConnectionTimeout();

        assertThat(registry.timer("hikaricp.connections.acquire").count()).isEqualTo(1);
        assertThat(registry.timer("hikaricp.connections.usage").count()).isEqualTo(1);
        assertThat(registry.timer("hikaricp.connections.creation").count()).isEqualTo(1);
        assertThat(registry.counter("hikaricp.connections.timeout").count()).isEqualTo(1);
        // 1_000_000 нс = 1 мс: длительность приходит в наносекундах и в прибор попадает как есть.
        assertThat(registry.timer("hikaricp.connections.acquire")
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(1d);
    }

    @Test
    @DisplayName("пересозданный пул пишет в те же приборы, а не плодит дубли")
    void recreateReusesMeters() {
        // Реестр prometheus, а не простой: в простом гистограмма регистрирует свои границы
        // отдельными приборами, и проверка на количество приборов считала бы их, а не наши.
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        HikariEventMetrics metrics = new HikariEventMetrics(registry);

        metrics.create("pool", null).recordConnectionAcquiredNanos(1_000L);
        var first = registry.timer("hikaricp.connections.acquire");
        // То же имя пула после recreate: приборы привязаны к имени, а не к поколению.
        metrics.create("pool", null).recordConnectionAcquiredNanos(1_000L);
        metrics.create("pool", null).recordConnectionTimeout();

        assertThat(registry.timer("hikaricp.connections.acquire").count()).isEqualTo(2);
        assertThat(registry.counter("hikaricp.connections.timeout").count()).isEqualTo(1);
        // Не новый прибор, а тот же: пересозданный пул продолжает ту же серию.
        assertThat(registry.timer("hikaricp.connections.acquire")).isSameAs(first);
        assertThat(registry.getMeters())
                .filteredOn(m -> m.getId().getName().startsWith("hikaricp."))
                .hasSize(4);
    }

    @Test
    @DisplayName("ожидание выдачи уходит в гистограмму, а не в summary")
    void timersAreHistograms() {
        // Проверка на текстовом формате, а не на типе прибора: summary отдаёт только _count/_sum/
        // _max, перцентилей из него не собрать, и панель ожидания молчит — молча, без ошибок.
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        HikariEventMetrics metrics = new HikariEventMetrics(registry);

        metrics.create("pool", null).recordConnectionAcquiredNanos(150_000L);
        String scrape = registry.scrape();

        assertThat(scrape).contains("# TYPE hikaricp_connections_acquire_seconds histogram");
        // Границы из кода видны в выводе: граница 0.1с должна попасть в ле.
        assertThat(scrape).contains("hikaricp_connections_acquire_seconds_bucket{le=\"0.1\"}");
    }

    @Test
    @DisplayName("границы гистограммы переживают пересоздание пула")
    void recreateKeepsHistogram() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        HikariEventMetrics metrics = new HikariEventMetrics(registry);

        metrics.create("pool", null).recordConnectionAcquiredNanos(150_000L);
        metrics.create("pool", null).recordConnectionAcquiredNanos(150_000L);

        String scrape = registry.scrape();
        // Ряд один: второе поколение продолжило ту же гистограмму, а не завело свою.
        assertThat(scrape.split("# TYPE hikaricp_connections_acquire_seconds histogram", -1).length - 1)
                .isEqualTo(1);
        assertThat(scrape).contains("hikaricp_connections_acquire_seconds_count 2");
        assertThat(scrape).contains("hikaricp_connections_acquire_seconds_bucket{le=\"+Inf\"} 2");
        // Крайние заданные границы на месте, прибор не скатился к дефолтным.
        assertThat(scrape).contains("hikaricp_connections_acquire_seconds_bucket{le=\"1.0E-4\"}");
        assertThat(scrape).contains("hikaricp_connections_acquire_seconds_bucket{le=\"5.0\"}");
    }

    @Test
    @DisplayName("закрытие прибора не теряет историю событий")
    void closeKeepsHistory() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HikariEventMetrics metrics = new HikariEventMetrics(registry);

        var tracker = metrics.create("pool", null);
        tracker.recordConnectionAcquiredNanos(1_000L);
        tracker.close();

        assertThat(registry.timer("hikaricp.connections.acquire").count()).isEqualTo(1);
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .extracting(id -> id.getName())
                .contains("hikaricp.connections.acquire");
    }
}
