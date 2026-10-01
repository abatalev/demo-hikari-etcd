package com.example.poolsvc.metrics;

import com.example.poolsvc.etcd.EtcdPoolConfigSource;
import com.example.poolsvc.pool.HikariSettings;
import com.example.poolsvc.pool.ManagedPool;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Наблюдение пула, источника конфигурации и гейта трафика в виде метрик.
 *
 * <p>Единственный источник величин — снимок {@link ManagedPool#runtime()} и состояние источника
 * конфигурации. Ни одна метрика не обращается к базе: сбор идёт насыщенному пулу не реже, чем
 * раз в несколько секунд, и брать ради этого соединение — значит усугублять очередь, ради которой
 * наблюдение и нужно. Правда от базы ({@code pg_stat_activity}) остаётся в HTTP-наблюдении и в
 * метриках сборщика postgres.
 *
 * <p>Имена метрик записаны точками, как их принято в Micrometer; в текстовом формате prometheus
 * точки становятся подчёркиваниями ({@code pool.connections.open} → {@code pool_connections_open}).
 *
 * <p>Чтение защищено: сбой чтения MBean'а не должен ронять сбор целиком, поэтому любая
 * неожиданность даёт ноль, а подробность уходит в лог. Обратная сторона: метрика может показать
 * ноль там, где наблюдение через API вернёт ошибку, — сбор обязан быть дешёвым и неотказным.
 *
 * <p>Состояние каждого гейджа — временная лямбда, а Micrometer держит объект состояния
 * {@link Gauge#builder(String, Object, java.util.function.ToDoubleFunction)} <b>слабой ссылкой</b>:
 * собранная лямбда означала бы, что ряд перестаёт отдавать значение и в текстовом формате
 * prometheus становится {@code NaN}. Поэтому все состояния собраны в {@link #gaugeStates} и живут
 * до конца процесса.
 */
@Component
public class PoolGauges {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger("metrics");

    /** Метка состояния конфигурации: закрытый набор, свободный текст в метрики не попадает. */
    private static final String[] CONFIG_STATES = {"none", "applied", "rejected"};

    /** Метка причины закрытой готовности: закрытый набор, как и состояние конфигурации. */
    private static final String[] NOT_READY_CAUSES = {"none", "no_config_keys", "etcd_unavailable"};

    private final ManagedPool pool;
    private final EtcdPoolConfigSource source;

    /**
     * Сильные ссылки на состояния всех гейджей: без них собранные лямбды убивают ряды (см. класс).
     */
    private final List<Object> gaugeStates = new ArrayList<>();

    public PoolGauges(MeterRegistry registry, ManagedPool pool, EtcdPoolConfigSource source) {
        this.pool = pool;
        this.source = source;
        registerPoolGauges(registry);
        registerSourceGauges(registry);
    }

    private void registerPoolGauges(MeterRegistry registry) {
        gauge(registry, "pool.maximum_pool_size", "текущий потолок пула",
                () -> runtime().maximumPoolSize());
        gauge(registry, "pool.minimum_idle", "текущий минимум idle-соединений",
                () -> runtime().minimumIdle());
        gauge(registry, "pool.generation", "номер поколения пула, растёт при пересоздании",
                () -> runtime().generation());
        gauge(registry, "pool.connections.open", "открытые соединения обоих поколений",
                () -> runtime().total());
        gauge(registry, "pool.connections.busy", "занятые соединения обоих поколений",
                () -> runtime().active());
        gauge(registry, "pool.connections.idle", "свободные соединения текущего поколения",
                () -> runtime().idle());
        gauge(registry, "pool.connections.awaiting", "потоки, ожидающие соединение",
                () -> runtime().threadsAwaitingConnection());
        // 1 — пула нет или он закрыт (конфигурация снята, инстанс без доли бюджета).
        gauge(registry, "pool.closing", "пула нет или он закрыт (1) либо пул работает (0)",
                () -> runtime().closed() ? 1 : 0);
        gauge(registry, "pool.evictable_idle", "соединения освобождаемого поколения",
                () -> runtime().drainingTotal());
        gauge(registry, "pool.unreleased_shrink", "неосвобождённое сжатие: удерживается сверх потолка",
                () -> runtime().unreleasedConnections());

        // Применённая конфигурация: по ней виден целевой потолок, даже когда пул уже снят.
        gauge(registry, "pool.config.maximum_pool_size", "применённый потолок из конфигурации etcd",
                () -> settings() == null ? 0 : settings().maximumPoolSize());
        gauge(registry, "pool.config.minimum_idle", "применённый минимум idle из конфигурации etcd",
                () -> settings() == null ? 0 : settings().minimumIdle());
        gauge(registry, "pool.config.connection_timeout_ms", "применённый таймаут ожидания соединения, мс",
                () -> settings() == null ? 0L : settings().connectionTimeoutMs());
    }

    private void registerSourceGauges(MeterRegistry registry) {
        gauge(registry, "pool.etcd.enabled", "источник конфигурации включён",
                () -> source.isEnabled() ? 1 : 0);
        gauge(registry, "pool.etcd.connected", "etcd отвечает: снимок получен",
                () -> source.isConnected() ? 1 : 0);
        // Подписка жива, когда запущен её цикл; connected — это ещё и удавшийся снимок.
        gauge(registry, "pool.etcd.watch_active", "цикл подписки запущен",
                () -> source.isWatchActive() ? 1 : 0);
        gauge(registry, "pool.etcd.revision", "ревизия etcd, на которой основан текущий снимок",
                () -> source.status().revision());
        // Журнал за всё время процесса, а не «что сломано сейчас» — так же, как в /api/config.
        gauge(registry, "pool.etcd.problems", "есть непрочитанные значения в etcd (журнал за процесс)",
                () -> source.status().problems().isEmpty() ? 0 : 1);
        gauge(registry, "pool.traffic_gate_open", "инстанс готов принимать трафик",
                () -> source.isTrafficAllowed() ? 1 : 0);

        for (String state : CONFIG_STATES) {
            gauge(registry, "pool.config.state", "состояние конфигурации: none/applied/rejected",
                    Tags.of("state", state), () -> configStateValue(state));
        }
        for (String cause : NOT_READY_CAUSES) {
            gauge(registry, "pool.not_ready_reason",
                    "причина закрытой готовности: none/no_config_keys/etcd_unavailable",
                    Tags.of("reason", cause), () -> notReadyCauseValue(cause));
        }
    }

    /** Какое значение принимает ряд {@code pool_config_state} с заданной меткой. */
    private int configStateValue(String state) {
        if (!source.isEnabled()) {
            // Источник выключен: конфигурация не из etcd, но и не «отсутствует».
            return "none".equals(state) ? 1 : 0;
        }
        if (source.status().lastOutcome() == ManagedPool.Outcome.REJECTED) {
            return "rejected".equals(state) ? 1 : 0;
        }
        return "applied".equals(state) ? 1 : 0;
    }

    /** Какое значение принимает ряд {@code pool_not_ready_reason} с заданной меткой. */
    private int notReadyCauseValue(String cause) {
        EtcdPoolConfigSource.NotReadyCause actual = source.notReadyCause();
        if (actual == null) {
            // Трафик открыт (или источник выключен): «ни одной из причин». Ряд причины, отличной
            // от none, при этом нулевой — иначе «нет причины» и «причина неизвестна» неразличимы.
            return "none".equals(cause) ? 1 : 0;
        }
        return actual.metricName().equals(cause) ? 1 : 0;
    }

    private ManagedPool.Runtime runtime() {
        return pool.runtime();
    }

    private HikariSettings settings() {
        return pool.settings();
    }

    /**
     * Регистрирует ряд, который обязан не бросать: ошибка чтения — это ноль и запись в лог,
     * а не оборванный сбор остальных метрик.
     */
    private void gauge(MeterRegistry registry, String name, String description,
            Supplier<? extends Number> value) {
        gauge(registry, name, description, Tags.empty(), value);
    }

    /**
     * Ряд с меткой из закрытого набора значений. Набор фиксирован при регистрации: он конечен,
     * поэтому график по нему строится, а реестр не растёт со временем.
     */
    private void gauge(MeterRegistry registry, String name, String description, Tags tags,
            Supplier<? extends Number> value) {
        gaugeStates.add(value);
        Gauge.builder(name, value, v -> read(name, v))
                .description(description)
                .tags(tags)
                .register(registry);
    }

    private double read(String name, Supplier<? extends Number> value) {
        try {
            Number v = value.get();
            return v == null ? 0d : v.doubleValue();
        } catch (RuntimeException e) {
            log.debug("метрика {} не снята: {}", name, e.toString());
            return 0;
        }
    }
}
