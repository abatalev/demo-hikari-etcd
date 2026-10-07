package com.abatalev.demo.etcdhikari.provisor.metrics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Наблюдение провижёра: величины флота, по которым он принимает решение о росте, и счётчики
 * принятых решений.
 *
 * <p>Общий признак {@code replica} задаётся конфигурацией (то же имя, что в ключе выборов), а
 * {@code service} — обслуживаемый сервис: один ряд на сервис, а не на узел. Состав узлов меняется,
 * и ряд на инстанс означал бы несуществующую кардинальность; подробности по узлам остаются в
 * журнале и в самом хранилище конфигурации.
 *
 * <p>Ряды читают последние известные величины и ничего не опрашивают: сбор метрик не ходит в
 * etcd. Поэтому при обрыве хранилища величины остаются прежними, а их недостоверность показывает
 * отдельный признак {@code provision_tree_stale} — иначе «последнее известное» читалось бы как
 * «текущее».
 *
 * <p>Все имена записаны точками, как принято в Micrometer; в текстовом формате prometheus точки
 * становятся подчёркиваниями, а счётчики получают суффикс {@code _total}.
 */
@Component
public class ProvisionerMetrics {

    private final MeterRegistry registry;
    private final Map<String, ServiceState> states = new ConcurrentHashMap<>();

    private final Counter recompute;
    private final Counter commandGrow;
    private final Counter commandShrink;
    private final Counter prefixWipes;
    private final Counter stuckDebtWarnings;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "MeterRegistry — Spring-бин, разделяется по дизайну")
    public ProvisionerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.recompute = registry.counter("provision.recompute");
        this.commandGrow = registry.counter("provision.commands.grow");
        this.commandShrink = registry.counter("provision.commands.shrink");
        this.prefixWipes = registry.counter("provision.prefix.wipes");
        this.stuckDebtWarnings = registry.counter("provision.stuck_debt.warnings");
    }

    /** Величины флота одного сервиса: ряд создаётся при первом обращении и живёт до конца процесса. */
    public ServiceState state(String service) {
        return states.computeIfAbsent(service, this::register);
    }

    /**
     * Имена сервисов, по которым уже заведено состояние.
     *
     * <p>Нужно, чтобы снять признак лидерства разом у всех: при сбросе аренды выборов воркеры
     * останавливаются для всех сервисов сразу, и признак обязан погаснуть там же — иначе ряд
     * утверждает «ведёт» у реплики, которая уже ничего не ведёт.
     */
    public java.util.Set<String> services() {
        return java.util.Set.copyOf(states.keySet());
    }

    private ServiceState register(String service) {
        Tags tags = Tags.of("service", service);
        ServiceState state = new ServiceState();
        gauge(tags, "provision.fleet.budget_active", "бюджет активного флота N", state.budget);
        gauge(tags, "provision.fleet.budget_inactive", "резерв неактивного флота R", state.reserve);
        gauge(tags, "provision.fleet.min_share", "минимальная доля активного инстанса m", state.minShare);
        gauge(tags, "provision.fleet.nodes", "живых узлов регистрации у сервиса", state.nodes);
        gauge(tags, "provision.fleet.active_nodes", "из них в активных группах", state.activeNodes);
        gauge(tags, "provision.fleet.nodes_without_share", "узлов, которым место не досталось", state.withoutShare);
        gauge(tags, "provision.fleet.sum_ceilings", "сумма приказанных размеров по узлам", state.sumCeilings);
        gauge(tags, "provision.fleet.sum_reported_debt",
                "сумма неосвобождённого сжатия по подтверждённым публикациям", state.sumDebt);
        // Верхняя оценка занятого места: пока инстанс не подтвердил освобождение, она законно выше
        // бюджета. Потолок защищает не она, а сумма приказанных размеров и её наблюдённый максимум.
        gauge(tags, "provision.fleet.sum_held_estimate", "оценка занятого места: потолок плюс долг", state.sumHeld);
        gauge(tags, "provision.fleet.sum_held_peak_since_reset",
                "максимум оценки занятого места с последнего пересчёта состава", state.sumHeldPeak);
        gauge(tags, "provision.fleet.growable_free", "место, свободное для роста", state.growableFree);
        gauge(tags, "provision.fleet.stuck_shrink_nodes",
                "узлов с подтверждённым сжатием, которое не уменьшается дольше порога",
                state.stuckShrinkNodes);
        gauge(tags, "provision.tree_stale", "снимок дерева сервиса устарел: etcd недоступен", state.stale);
        gauge(tags, "provision.leader", "реплика ведёт этот сервис", state.leader);
        return state;
    }

    private void gauge(Tags tags, String name, String description, AtomicLong holder) {
        Gauge.builder(name, holder, AtomicLong::get)
                .description(description)
                .tags(tags)
                .register(registry);
    }

    /** Полный пересчёт состава сервиса: доли, состав и уборка пересчитаны заново. */
    public void recompute() {
        recompute.increment();
    }

    /** Приказ, увеличивший размер пула узла (запись в etcd состоялась). */
    public void commandGrow() {
        commandGrow.increment();
    }

    /** Приказ, уменьшивший размер пула узла (запись в etcd состоялась). */
    public void commandShrink() {
        commandShrink.increment();
    }

    /** Префикс конфигурации очищен: ушедший узел, избыток или холодный флот. */
    public void prefixWipe() {
        prefixWipes.increment();
    }

    /** Зависшее неосвобождённое сжатие попало в журнал: место не перераспределяется. */
    public void stuckDebtWarning() {
        stuckDebtWarnings.increment();
    }

    /** Величины одного сервиса; пишет провижёр под своим замком, читает сбор метрик. */
    public static final class ServiceState {

        final AtomicLong budget = new AtomicLong();
        final AtomicLong reserve = new AtomicLong();
        final AtomicLong minShare = new AtomicLong();
        final AtomicLong nodes = new AtomicLong();
        final AtomicLong activeNodes = new AtomicLong();
        final AtomicLong withoutShare = new AtomicLong();
        final AtomicLong sumCeilings = new AtomicLong();
        final AtomicLong sumDebt = new AtomicLong();
        final AtomicLong sumHeld = new AtomicLong();
        final AtomicLong sumHeldPeak = new AtomicLong();
        final AtomicLong growableFree = new AtomicLong();
        final AtomicLong stuckShrinkNodes = new AtomicLong();
        final AtomicLong stale = new AtomicLong();
        final AtomicLong leader = new AtomicLong();

        /** Оценка занятого места по снимку: та величина, ради которой всё это и считается. */
        public void fleet(FleetSample sample, boolean fullRecompute) {
            budget.set(sample.budget());
            reserve.set(sample.reserve());
            minShare.set(sample.minShare());
            nodes.set(sample.nodes());
            activeNodes.set(sample.activeNodes());
            withoutShare.set(sample.withoutShare());
            sumCeilings.set(sample.sumCeilings());
            sumDebt.set(sample.sumConfirmedDebt());
            sumHeld.set(sample.sumHeld());
            growableFree.set(sample.growableFree());
            if (fullRecompute) {
                // Состав пересчитан заново — отсчёт максимума начинается с текущей оценки.
                sumHeldPeak.set(sample.sumHeld());
            } else {
                sumHeldPeak.accumulateAndGet(sample.sumHeld(), Math::max);
            }
        }

        /**
         * Получен ли свежий снимок дерева сервиса.
         *
         * <p>Обрыв хранилища обнуляет признак, но не величины: конфигурация и пул продолжают
         * жить на последнем применённом состоянии, и обнулять наблюдение вместе с ними значило бы
         * потерять и то, что инстанс продолжает обслуживать трафик по старой доле.
         */
        public void snapshotReceived(boolean received) {
            stale.set(received ? 0 : 1);
        }

        /**
         * Число узлов с зависшим неосвобождённым сжатием на последнем проходе.
         *
         * <p>Текущее состояние, а не счётчик предупреждений: по счётчику правило держалось бы
         * сработавшим ещё после того, как сжатие освободилось.
         */
        public void stuckShrink(int nodes) {
            stuckShrinkNodes.set(nodes);
        }

        /** Ведёт ли эта реплика сервис: ряд есть у обеих реплик, единица — у ведущей. */
        public void leading(boolean value) {
            leader.set(value ? 1 : 0);
        }

        public long sumHeld() {
            return sumHeld.get();
        }

        public long sumHeldPeak() {
            return sumHeldPeak.get();
        }
    }

    /**
     * Снимок величин флота, по которому провижёр принял решение.
     *
     * <p>Оценка занятости ({@code sumHeld}) — верхняя оценка: отсутствующая или относящаяся к
     * прежнему потолку публикация трактуется как удержание всего, что инстанс мог держать. Рядом
     * с ней {@code sumConfirmedDebt} — сколько флот действительно держит сверх потолков, по
     * подтверждённым публикациям; на штатном перезапуске флота, где публикаций нет ни у кого, эти
     * две величины расходятся целиком.
     */
    public record FleetSample(int budget, int minShare, int reserve, int nodes, int activeNodes,
            int withoutShare, int sumCeilings, int sumConfirmedDebt, int sumHeld, int growableFree) {
    }
}