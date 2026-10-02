package com.abatalev.demo.etcdhikari.provisor.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Величины флота в метриках: что попадает в ряд, как живёт максимум оценки занятого места и что
 * происходит с величинами при обрыве хранилища.
 *
 * <p>Логика обновления вынесена в чистое состояние без обращения к etcd, поэтому проверяется
 * напрямую. Сверка самих величин с реальным снимком — на стенде (см. `make budget`).
 */
class ProvisionerMetricsTest {

    private static ProvisionerMetrics.FleetSample sample(int budget, int sumHeld) {
        return new ProvisionerMetrics.FleetSample(budget, 1, 1, 4, 4, 0, 100, 0, sumHeld, 0);
    }

    /** Образец, где оценка занятости и подтверждённый долг расходятся: часть узлов без публикаций. */
    private static ProvisionerMetrics.FleetSample sample(int budget, int sumHeld, int sumConfirmed) {
        return new ProvisionerMetrics.FleetSample(budget, 1, 1, 4, 4, 0, 100, sumConfirmed,
                sumHeld, 0);
    }

    @Test
    @DisplayName("величины флота видны с признаком сервиса и не смешиваются между сервисами")
    void fleetValuesArePerService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);

        metrics.state("service-a").fleet(sample(100, 90), true);
        metrics.state("service-b").fleet(sample(50, 50), true);

        assertThat(registry.get("provision.fleet.budget_active").tag("service", "service-a")
                .gauge().value()).isEqualTo(100d);
        assertThat(registry.get("provision.fleet.sum_held_estimate").tag("service", "service-b")
                .gauge().value()).isEqualTo(50d);
        assertThat(registry.get("provision.fleet.sum_ceilings").tag("service", "service-a")
                .gauge().value()).isEqualTo(100d);
    }

    @Test
    @DisplayName("максимум оценки копится между пересчётами и обнуляется на пересчёте состава")
    void peakAccumulatesAndResets() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);
        ProvisionerMetrics.ServiceState state = metrics.state("service-a");

        state.fleet(sample(100, 60), true);
        assertThat(state.sumHeldPeak()).isEqualTo(60);

        // Пересчёт по публикации (не по составу): максимум обязан вырасти, а не сброситься.
        state.fleet(sample(100, 95), false);
        assertThat(state.sumHeldPeak()).isEqualTo(95);
        state.fleet(sample(100, 70), false);
        assertThat(state.sumHeldPeak()).isEqualTo(95);

        // Пересчёт состава: отсчёт начинается заново с текущей оценки.
        state.fleet(sample(100, 40), true);
        assertThat(state.sumHeldPeak()).isEqualTo(40);
        assertThat(state.sumHeld()).isEqualTo(40);
    }

    @Test
    @DisplayName("обрыв хранилища не обнуляет величины, а помечает снимок устаревшим")
    void staleSnapshotKeepsValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);
        ProvisionerMetrics.ServiceState state = metrics.state("service-a");

        state.fleet(sample(100, 90), true);
        state.snapshotReceived(false);

        assertThat(registry.get("provision.tree_stale").tag("service", "service-a")
                .gauge().value()).isEqualTo(1d);
        // Величины остались прежними: конфигурация продолжает жить на последнем применённом.
        assertThat(registry.get("provision.fleet.sum_held_estimate").tag("service", "service-a")
                .gauge().value()).isEqualTo(90d);

        state.snapshotReceived(true);
        assertThat(registry.get("provision.tree_stale").tag("service", "service-a")
                .gauge().value()).isZero();
    }

    @Test
    @DisplayName("признак ведущей работы есть у обеих реплик и равен единице у ведущей")
    void leadingFlagIsPerService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);

        metrics.state("service-a").leading(true);
        metrics.state("service-a"); // вторая реплика увидела тот же сервис, но ведёт его другая

        assertThat(registry.get("provision.leader").tag("service", "service-a").gauge().value())
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("счётчики решений растут по мере решений")
    void decisionCountersGrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);

        metrics.recompute();
        metrics.commandGrow();
        metrics.commandShrink();
        metrics.commandShrink();
        metrics.prefixWipe();
        metrics.stuckDebtWarning();

        assertThat(registry.counter("provision.recompute").count()).isEqualTo(1d);
        assertThat(registry.counter("provision.commands.grow").count()).isEqualTo(1d);
        assertThat(registry.counter("provision.commands.shrink").count()).isEqualTo(2d);
        assertThat(registry.counter("provision.prefix.wipes").count()).isEqualTo(1d);
        assertThat(registry.counter("provision.stuck_debt.warnings").count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("подтверждённое сжатие видно отдельно от верхней оценки")
    void confirmedDebtIsSeparateFromEstimate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);

        // Оценка занятости — худший случай (часть узлов не отчиталась), подтверждённый долг — нет.
        metrics.state("service-a").fleet(sample(100, 150, 10), true);

        assertThat(registry.get("provision.fleet.sum_held_estimate").tag("service", "service-a")
                .gauge().value()).isEqualTo(150d);
        assertThat(registry.get("provision.fleet.sum_reported_debt").tag("service", "service-a")
                .gauge().value()).isEqualTo(10d);
    }

    @Test
    @DisplayName("зависшее сжатие — текущее число узлов, а не счётчик предупреждений")
    void stuckShrinkIsCurrentState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);
        ProvisionerMetrics.ServiceState state = metrics.state("service-a");

        state.stuckShrink(2);
        state.stuckShrink(2);
        assertThat(registry.get("provision.fleet.stuck_shrink_nodes").tag("service", "service-a")
                .gauge().value()).isEqualTo(2d);

        // Освободилось: ряд идёт в ноль сразу, без ожидания окна счётчика.
        state.stuckShrink(0);
        assertThat(registry.get("provision.fleet.stuck_shrink_nodes").tag("service", "service-a")
                .gauge().value()).isZero();
        // Счётчик предупреждений при этом остался: он помнит, что предупреждение было.
        metrics.stuckDebtWarning();
        assertThat(registry.counter("provision.stuck_debt.warnings").count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("снятие лидерства гасит признак у всех сервисов разом")
    void droppingLeadershipClearsEveryService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);

        metrics.state("service-a").leading(true);
        metrics.state("service-b").leading(true);
        // Сброс аренды выборов останавливает воркеры всех сервисов сразу — признак обязан погаснуть
        // там же. Иначе после обрыва etcd ряд утверждал бы «ведёт» у реплики, которая уже ничего
        // не ведёт, а величины флота при этом застывшие.
        assertThat(metrics.services()).containsExactlyInAnyOrder("service-a", "service-b");

        for (String service : metrics.services()) {
            metrics.state(service).leading(false);
        }

        assertThat(registry.get("provision.leader").tag("service", "service-a").gauge().value())
                .isZero();
        assertThat(registry.get("provision.leader").tag("service", "service-b").gauge().value())
                .isZero();
    }

    @Test
    @DisplayName("остановленный воркер помечает снимок устаревшим, величины не обнуляет")
    void stoppedWorkerMarksSnapshotStale() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);
        ProvisionerMetrics.ServiceState state = metrics.state("service-a");

        state.fleet(sample(100, 100), true);
        state.snapshotReceived(true);

        // Остановка воркера (сброс аренды выборов, перехват лидерства, остановка реплики):
        // числа застывают последними известными, и признак обязан сказать, что они не свежие.
        state.snapshotReceived(false);

        assertThat(registry.get("provision.tree_stale").tag("service", "service-a").gauge().value())
                .isEqualTo(1d);
        // Величины на месте: конфигурация и пул живут на последнем применённом состоянии.
        assertThat(registry.get("provision.fleet.sum_ceilings").tag("service", "service-a")
                .gauge().value()).isEqualTo(100d);
    }

    @Test
    @DisplayName("повторный пересчёт того же сервиса не плодит дубли рядов")
    void repeatedRecomputeKeepsOneSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProvisionerMetrics metrics = new ProvisionerMetrics(registry);

        for (int i = 0; i < 3; i++) {
            metrics.state("service-a").fleet(sample(100, 100), true);
        }

        assertThat(registry.getMeters())
                .filteredOn(m -> m.getId().getName().startsWith("provision.fleet."))
                .hasSize(12);
    }
}