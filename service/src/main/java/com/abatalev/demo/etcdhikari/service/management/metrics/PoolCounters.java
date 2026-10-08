package com.abatalev.demo.etcdhikari.service.management.metrics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Счётчики решений сервиса: сколько конфигураций пришло, сколько отклонено, как менялся пул.
 *
 * <p>Отдельный компонент, а не часть {@code ManagedPool}, потому что решения принимают два
 * владельца: пул (ресайз, пересоздание, отклонение после нормализации) и источник конфигурации
 * (отклонение значения из etcd, непрочитанные значения). Счётчики ничего не знают о пуле и
 * ничего не опрашивают — их значение меняется в момент решения, а не в момент сбора метрик.
 */
@Component
public class PoolCounters {

    private final MeterRegistry registry;
    private final Counter configApplied;
    private final Counter configRejected;
    private final Counter configUnreadable;
    private final Counter resizeGrow;
    private final Counter resizeShrink;
    private final Counter recreated;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "MeterRegistry — Spring-бин, разделяется по дизайну")
    public PoolCounters(MeterRegistry registry) {
        this.registry = registry;
        this.configApplied = registry.counter("pool.config.applied");
        this.configRejected = registry.counter("pool.config.rejected");
        this.configUnreadable = registry.counter("pool.config.unreadable");
        this.resizeGrow = registry.counter("pool.resize.grow");
        this.resizeShrink = registry.counter("pool.resize.shrink");
        this.recreated = registry.counter("pool.recreate");
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "MeterRegistry — Spring-бин, оборачивать нечего")
    public MeterRegistry registry() {
        return registry;
    }

    /** Конфигурация применена (в том числе когда пул только что создан или пересоздан). */
    public void configApplied() {
        configApplied.increment();
    }

    /** Конфигурация отклонена: пул остался на прежних значениях. */
    public void configRejected() {
        configRejected.increment();
    }

    /** Ключ etcd, значение которого не удалось прочитать (оно игнорируется, остальные применяются). */
    public void configUnreadable() {
        configUnreadable.increment();
    }

    /** Размер пула вырос. */
    public void resizeGrow() {
        resizeGrow.increment();
    }

    /** Размер пула уменьшился. */
    public void resizeShrink() {
        resizeShrink.increment();
    }

    /** Пул пересоздан (сменилась целевая часть конфигурации). */
    public void recreated() {
        recreated.increment();
    }
}
