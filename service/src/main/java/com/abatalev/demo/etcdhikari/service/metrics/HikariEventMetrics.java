package com.abatalev.demo.etcdhikari.service.metrics;

import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * События пула через SPI HikariCP: сколько ждали выдачи соединения, сколько его удерживали,
 * как долго открывали и сколько раз выдача не дождалась.
 *
 * <p>Почему не {@code com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory},
 * который HikariCP 6.3.3 уже умеет: его гейджи навсегда привязываются к статистике
 * <em>первого</em> поколения пула, и после пересоздания или снятия конфигурации они продолжают
 * читать закрытый пул и показывают ноль. Стенд делает ровно это — целевой ноль у холодной группы,
 * затем возврат к тёплой, — поэтому состояние пула берётся из живого поколения (доменные гейджи
 * {@link PoolGauges} читают снимок {@code ManagedPool.runtime()}), а отсюда берутся только события.
 */
public class HikariEventMetrics implements MetricsTrackerFactory {

    /**
     * Границы гистограмм, в секундах.
     *
     * <p>Заданы явно: дефолтные границы Micrometer ориентированы на веб-приложение и для пула
     * слишком грубы. Время выдачи соединения — сотые доли миллисекунды в норме и секунды при
     * насыщении, удержание — единицы и сотни миллисекунд, открытие соединения — десятки
     * миллисекунд. Без заданных границ прибор уходит в summary ({@code _count}, {@code _sum},
     * {@code _max}), перцентилей не видно, и панель ожидания соединения остаётся пустой.
     */
    private static final Duration[] ACQUIRE_BUCKETS = seconds(
            0.0001, 0.0005, 0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 3, 5);
    private static final Duration[] USAGE_BUCKETS = seconds(
            0.001, 0.005, 0.01, 0.05, 0.1, 0.2, 0.5, 1, 5);
    private static final Duration[] CREATION_BUCKETS = seconds(
            0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1);

    private static Duration[] seconds(double... values) {
        Duration[] result = new Duration[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = Duration.ofNanos(Math.round(values[i] * 1_000_000_000d));
        }
        return result;
    }

    private final MeterRegistry registry;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "MeterRegistry — Spring-бин, разделяется по дизайну")
    public HikariEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public IMetricsTracker create(String poolName, PoolStats poolStats) {
        // Пул пересоздаётся с тем же именем, а приборы событий переиспользуются по имени:
        // перерегистрация на каждый recreate плодила бы дубли с тем же id. Отдельной метки на
        // имя пула нет — пул в процессе один, и его уже называет общий признак node.
        return new EventTracker();
    }

    private Timer histogram(String name, Duration[] buckets) {
        // register(), а не registry.timer(): конфигурация гистограммы задаётся только при первой
        // регистрации, а уже зарегистрированный прибор вернётся как есть — второе поколение пула
        // продолжит ту же серию вместе с накопленными границами.
        return Timer.builder(name)
                .publishPercentileHistogram()
                .serviceLevelObjectives(buckets)
                .register(registry);
    }

    private final class EventTracker implements IMetricsTracker {

        private final Timer acquire;
        private final Timer usage;
        private final Timer creation;
        private final Counter timeouts;

        EventTracker() {
            this.acquire = histogram("hikaricp.connections.acquire", ACQUIRE_BUCKETS);
            this.usage = histogram("hikaricp.connections.usage", USAGE_BUCKETS);
            this.creation = histogram("hikaricp.connections.creation", CREATION_BUCKETS);
            this.timeouts = registry.counter("hikaricp.connections.timeout");
        }

        @Override
        public void recordConnectionAcquiredNanos(long connectionAcquiredNanos) {
            acquire.record(Duration.ofNanos(connectionAcquiredNanos));
        }

        @Override
        public void recordConnectionUsageMillis(long connectionBorrowedAtMillis) {
            usage.record(Duration.ofMillis(connectionBorrowedAtMillis));
        }

        @Override
        public void recordConnectionCreatedMillis(long connectionCreatedMillis) {
            creation.record(Duration.ofMillis(connectionCreatedMillis));
        }

        @Override
        public void recordConnectionTimeout() {
            timeouts.increment();
        }

        @Override
        public void close() {
            // Приборы переживают пул: на закрытии поколения история событий не должна пропадать.
        }
    }
}
