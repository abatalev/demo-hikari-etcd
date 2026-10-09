package com.abatalev.demo.etcdhikari.service.management.otel;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Consumer;
import java.util.function.Function;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * События механизма в трассах: обычные Observation общего контура приложения.
 *
 * <p>События механизма — применение конфигурации, создание пула и дренаж, публикация
 * неосвобождённого сжатия, выдача соединения с ожиданием — заводятся в тот же
 * {@link ObservationRegistry}, что и обслуживание запросов, и уходят тем же путём:
 * отбираются общей долей {@code management.tracing.sampling.probability} и несут общий ресурс
 * сигнала приложения. Родительский спан наблюдение берёт из текущего контекста: событие, возникшее
 * в потоке запроса (ожидание соединения), становится частью трассы этого запроса, событие из
 * фонового потока (применение конфигурации, дренаж) — самостоятельной трассой.
 *
 * <p>Признаки события задаются лямбдой, которая исполняется до старта наблюдения; её ошибка
 * гасится с записью в журнал — признаки не имеют права отменить само событие: упавшая лямбда
 * признаков отменяла бы применение конфигурации целиком (регрессия, найденная на стенде:
 * разыменование nullable-поля настроек в признаке не давало создать пул, и восемь инстансов
 * стояли с 503). Событие, так и не начатое, выполняется без трассы.
 *
 * <p>Пароля в признаках события нет и быть не должно: в etcd он лежит открытым текстом, а трасса
 * — это ещё одно место, где он может утечь. Поэтому в признаки идут только имена полей, числа и
 * имена инстансов; сами значения конфигурации проходят через {@code PoolSize.diff()},
 * где пароль замаскирован.
 */
@Component
public class MechanismObservation {

    private static final Logger log = LoggerFactory.getLogger(MechanismObservation.class);

    /** Замена для мест, где наблюдения нет (тесты): события никуда не пишутся. */
    public static final MechanismObservation NOOP = new MechanismObservation(ObservationRegistry.create());

    private final ObservationRegistry registry;

    @Autowired
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Реестр наблюдения — общий контур приложения, и события механизма пишутся в него же")
    public MechanismObservation(ObservationRegistry registry) {
        this.registry = registry;
    }

    /** Мгновенное событие механизма (выборы, опубликованная величина). */
    public void event(String name, Consumer<Observation> attributes) {
        try (Event ignored = start(name, attributes)) {
            // тела нет: событие мгновенное, время выполнения нечего мерить
        }
    }

    /**
     * Открытое событие механизма.
     *
     * <p>Нужен там, где решение принимается несколькими выходами сразу (fail-closed у провижёра):
     * дописать итог в признаки удобнее по дороге, чем передавать признаки на вход и угадывать на
     * выходе. Закрывается по {@link Event#close()} — в обоих случаях, и при ошибке.
     */
    public Event start(String name, Consumer<Observation> attributes) {
        return new Event(open(name, attributes));
    }

    /**
     * Событие, тело которого бросить не может (пул): сбой попадает в трассу и журнал, но наружу не
     * идёт — наблюдение не имеет права изменить поведение пула.
     *
     * <p>Неожиданная ошибка тела всё равно пробрасывается: проглоченная ошибка создания пула была бы
     * ошибкой создания пула, только без следа.
     */
    public <T> T callQuietly(String name, Consumer<Observation> attributes, Function<Event, T> body) {
        Observation observation = open(name, attributes);
        Observation.Scope scope = observation.openScope();
        try {
            return body.apply(new Event(observation));
        } catch (RuntimeException | Error e) {
            // Провал виден в трассе, а не только в журнале: у трассы и журнала один идентификатор.
            observation.error(e);
            throw e;
        } finally {
            scope.close();
            observation.stop();
        }
    }

    /**
     * Событие, тело которого бросает (записи etcd): провал виден в трассе и журнале и идёт наружу.
     */
    public void run(String name, Consumer<Observation> attributes, ThrowingRunnable body) throws Exception {
        Observation observation = open(name, attributes);
        Observation.Scope scope = observation.openScope();
        try {
            body.run();
        } catch (Exception | Error e) {
            // Провал виден в трассе, а не только в журнале: у трассы и журнала один идентификатор.
            observation.error(e);
            throw e;
        } finally {
            scope.close();
            observation.stop();
        }
    }

    /**
     * Открыть событие так, чтобы отказ наблюдения не отменил само событие.
     *
     * <p>Тело события — это запись в etcd, применение конфигурации или дренаж: пропустить его из-за
     * неисправного наблюдения нельзя ни при каком раскладе. Если наблюдение отказало, событие
     * выполняется без него — возвращается наблюдение на пустом регистре ({@link #noop(String)}),
     * которое не пишет ничего, и вызывающий код не ветвится «если наблюдение включено».
     *
     * <p>Лямбда признаков исполняется до старта наблюдения, и её ошибка гасится с записью в журнал:
     * признаки — это наблюдение, а не механизм, и отменять событие они не имеют права (регрессия,
     * найденная на стенде: разыменование nullable-поля настроек в признаке не давало создать пул,
     * и восемь инстансов стояли с 503). Признак теряется, событие — нет.
     */
    private Observation open(String name, Consumer<Observation> attributes) {
        Observation observation;
        try {
            observation = Observation.createNotStarted(name, registry);
        } catch (RuntimeException e) {
            log.warn("[{}] событие механизма не началось, выполняется без трассы: {}", name, e.toString());
            return noop(name);
        }
        if (attributes != null) {
            try {
                attributes.accept(observation);
            } catch (RuntimeException e) {
                log.warn("[{}] признаки события не записаны: {}", name, e.toString());
            }
        }
        try {
            observation.start();
        } catch (RuntimeException e) {
            log.warn("[{}] событие механизма не началось, выполняется без трассы: {}", name, e.toString());
            return noop(name);
        }
        return observation;
    }

    /**
     * Наблюдение, которое не пишет ничего: пустой регистр без обработчиков.
     *
     * <p>Возвращается вместо настоящего, когда наблюдение отказало. {@code note}/{@code failure}/
     * {@code stop} на нём безопасны и пусты, поэтому {@link Event} и {@code call*}/{@code run}-методы
     * работают с не-null наблюдением без единой проверки.
     */
    private static Observation noop(String name) {
        Observation observation = Observation.createNotStarted(name, ObservationRegistry.create());
        observation.start();
        return observation;
    }

    /**
     * Открытое событие: пишет итог решения в признаки и закрывается сам.
     *
     * <p>Без наблюдения (тесты, отказ старта) это тот же объект — вызывающий код не ветвится
     * «если наблюдение включено», иначе такую ветку пришлось бы держать в каждом месте вызова.
     * Признаки, добавленные после старта, мост доносит до готового спана
     * (см. {@link MechanismObservationTest}): решение и его исход — одно событие.
     */
    public static final class Event implements AutoCloseable {

        private final Observation observation;

        private Event(Observation observation) {
            this.observation = observation;
        }

        public void note(String key, String value) {
            observation.getContext().addLowCardinalityKeyValue(KeyValue.of(key, value));
        }

        public void note(String key, long value) {
            note(key, Long.toString(value));
        }

        public void note(String key, boolean value) {
            note(key, Boolean.toString(value));
        }

        /** Сбой события: попадает и в трассу, и в журнал (у них общий идентификатор). */
        public void failure(Throwable t) {
            observation.error(t);
        }

        @Override
        public void close() {
            observation.stop();
        }
    }

    /** Тело события без результата, которому разрешено бросать: записи etcd это умеют. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}