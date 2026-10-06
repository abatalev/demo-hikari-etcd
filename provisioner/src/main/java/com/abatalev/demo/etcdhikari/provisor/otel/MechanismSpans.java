package com.abatalev.demo.etcdhikari.provisor.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * События механизма в трассах: отдельный трассировщик с постоянным отбором.
 *
 * <p>Обслуживание запросов отбирается по доле ({@code management.tracing.sampling.probability},
 * на стенде 100%, под нагрузкой её снижают). События механизма — пересчёт состава и распределения
 * бюджета, записи в хранилище конфигурации, выборы ведущей реплики — отбираются <em>всегда</em>: они редки, и по ним судят о переходе, поэтому снижение доли
 * обслуживания не должно уметь их съесть. Отдельный провайдер с {@link Sampler#alwaysOn()} — это
 * ровно то свойство, ради которого «просто держать 100%» и отклонено: при общем 100% первым
 * потерялось бы именно то, ради чего трассы и заведены.
 *
 * <p>Отправитель и ресурс берутся те же, что у приложения: адрес точки приёма, заголовки и признаки
 * инстанса ({@code node}, {@code group}, {@code service}) не дублируются, иначе механизм и
 * обслуживание попали бы в хранилище трасс под разными именами процесса. Общий отправитель не
 * закрывается здесь намеренно (см. {@link #stop()}).
 *
 * <p>Событие механизма — <em>отдельная</em> трасса, а не событие внутри трассы запроса: трасса
 * обслуживания может не попасть в хранилище (доля 0), и тогда событие механизма потеряло бы смысл.
 * Связь с запросом, из которого событие возникло, сохраняется ссылкой на его span.
 *
 * <p>Пароля в признаках события нет и быть не должно: в etcd он лежит открытым текстом, а трасса
 * — это ещё одно место, где он может утечь. Поэтому в признаки идут только имена полей, числа и
 * имена инстансов; сами значения конфигурации проходят через {@code PoolSize.diff()},
 * где пароль замаскирован.
 */
@Component
public class MechanismSpans implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MechanismSpans.class);

    /** Имя прибора в трассах: по нему видно, что событие механизма, а не обслуживание. */
    public static final String INSTRUMENTATION = "com.abatalev.demo.etcdhikari.provisor.mechanism";

    /** Заменятель для мест, где наблюдения нет (тесты, выключенная трассировка). */
    public static final MechanismSpans NOOP = new MechanismSpans(List.of(), Resource.empty());

    private final Tracer tracer;
    private final SdkTracerProvider provider;
    private volatile boolean running;

    /**
     * @param exporters отправители трасс приложения; пусто — трассировка выключена
     * @param resource ресурс сигнала приложения: признаки инстанса без него были бы другими
     */
    @Autowired
    public MechanismSpans(ObjectProvider<SpanExporter> exporters, ObjectProvider<Resource> resource) {
        this(exporters.stream().toList(), resource.getIfAvailable(Resource::empty));
    }

    /** Без Spring: отправители и ресурс задаются напрямую (тесты, подмены). */
    MechanismSpans(List<SpanExporter> available, Resource resource) {
        // Отправитель в стенде один (OTLP до точки приёма); при появлении второго берётся первый,
        // и это лучше, чем молчаливый выбор случайного: смена адреса приёма видна настройкой.
        if (available.isEmpty()) {
            // Трассировка выключена или отправителя нет: события mechanism просто некуда девать.
            // Место вызова при этом не меняется — ни один путь не должен ветвиться «если трассировка».
            this.tracer = OpenTelemetry.noop().getTracer(INSTRUMENTATION);
            this.provider = null;
            return;
        }
        // Свой пакетный отправитель: очередь mechanism не должна делиться с обслуживанием, иначе
        // всплеск запросов забил бы очередь и унёс с собой последние события перехода.
        BatchSpanProcessor processor = BatchSpanProcessor.builder(available.get(0)).build();
        this.provider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        this.tracer = this.provider.get(INSTRUMENTATION);
    }

    /** Мгновенное событие механизма (выборы, опубликованная величина). */
    public void event(String name, Consumer<SpanBuilder> attributes) {
        try (Event e = start(name, attributes)) {
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
    public Event start(String name, Consumer<SpanBuilder> attributes) {
        return new Event(startSpanOrInvalid(name, attributes));
    }

    /**
     * Событие механизма со своим временем выполнения.
     *
     * @param body тело события; получает открытый span, чтобы дописать в него свой итог (например,
     *     какое решение принято) — событие закрывается здесь же, в любом случае
     */
    public <T> T call(String name, Consumer<SpanBuilder> attributes, ThrowingFunction<Span, T> body)
            throws Exception {
        Span span = startSpanOrInvalid(name, attributes);
        try (Scope scope = span.makeCurrent()) {
            T result = body.apply(span);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception | Error e) {
            // Провал виден в трассе, а не только в журнале: у трассы и журнала один идентификатор.
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
        }
    }

    /** То же для тела без результата. */
    public void call(String name, Consumer<SpanBuilder> attributes, ThrowingConsumer<Span> body)
            throws Exception {
        call(name, attributes, span -> {
            body.accept(span);
            return null;
        });
    }

    /**
     * Событие, тело которого бросить не может (пул): сбой попадает в трассу и журнал, но наружу не
     * идёт — наблюдение не имеет права изменить поведение пула.
     *
     * <p>Неожиданная ошибка тела всё равно пробрасывается: проглоченная ошибка создания пула была бы
     * ошибкой создания пула, только без следа.
     */
    public <T> T callQuietly(String name, Consumer<SpanBuilder> attributes, Function<Span, T> body) {
        Span span = startSpanOrInvalid(name, attributes);
        try (Scope scope = span.makeCurrent()) {
            T result = body.apply(span);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (RuntimeException | Error e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
        }
    }

    /** То же без результата. */
    public void runQuietly(String name, Consumer<SpanBuilder> attributes, Runnable body) {
        callQuietly(name, attributes, span -> {
            body.run();
            return null;
        });
    }

    /** То же для тела, которому span не нужен. */
    public void run(String name, Consumer<SpanBuilder> attributes, ThrowingRunnable body) throws Exception {
        call(name, attributes, span -> {
            body.run();
            return null;
        });
    }

    /**
     * Открыть событие так, чтобы отказ наблюдения не отменил само событие.
     *
     * <p>Тело события — запись в etcd, пересчёт долей или отказ от них: пропустить его из-за
     * неисправного наблюдения нельзя ни при каком раскладе. Если трассировщик не смог открыть span,
     * событие выполняется без него — пустой span не пишет ничего и не мешает ничему.
     */
    private Span startSpanOrInvalid(String name, Consumer<SpanBuilder> attributes) {
        try {
            return startSpan(name, attributes);
        } catch (RuntimeException e) {
            log.warn("[{}] событие механизма не началось, выполняется без трассы: {}", name, e.toString());
            return Span.getInvalid();
        }
    }

    private Span startSpan(String name, Consumer<SpanBuilder> attributes) {
        // setNoParent: событие механизма — всегда отдельная трасса, даже когда вызвано из обслуживания
        // запроса. Продолжением чужой трассы оно было бы потерянным, если та не попала в хранилище
        // (доля отбора), и «приклеенным» к запросу, если попала. Связь с запросом — ссылкой ниже.
        SpanBuilder builder = tracer.spanBuilder(name).setNoParent();
        if (attributes != null) {
            try {
                attributes.accept(builder);
            } catch (RuntimeException e) {
                // Признаки — это наблюдение, а не механизм: ошибка в них не должна отменять событие.
                // Лямбда признаков исполняется ДО тела события, то есть наоборот: упавшая лямбда
                // отменяла бы саму операцию. На стенде на этом стоял весь флот: разыменование
                // nullable-поля настроек в признаке не давало инстансу создать пул. Признак
                // теряется, событие — нет.
                log.warn("[{}] признаки события не записаны: {}", name, e.toString());
            }
        }
        SpanContext serving = Span.fromContext(Context.current()).getSpanContext();
        if (serving.isValid()) {
            // Событие механизма — отдельная трасса, поэтому связь с обслуживанием ссылкой: иначе
            // трасса запроса, попав в хранилище (доля > 0), показала бы событие как потерянное
            // продолжение, а не как отдельный след.
            builder.addLink(serving);
            builder.setAttribute("serving.trace_id", serving.getTraceId());
            builder.setAttribute("serving.span_id", serving.getSpanId());
        }
        return builder.startSpan();
    }

    /**
     * Только сброс очереди, без закрытия.
     *
     * <p>Отправитель общий с приложением, и закрыл бы его здесь раньше, чем Boot закроет свой
     * провайдер: последние трассы обслуживания тогда не дошли бы до точки приёма. Сброса хватает —
     * события mechanism не переживают перезапуск процесса, и терять их незачем.
     */
    @Override
    public void stop() {
        running = false;
        flush();
    }

    /** Дослать накопленное (очередь пакетная); тестам это единственный способ дождаться отправки. */
    void flush() {
        SdkTracerProvider p = provider;
        if (p != null && !p.forceFlush().join(2, TimeUnit.SECONDS).isSuccess()) {
            log.warn("очередь событий механизма не опустела за 2с — последние события уйдут при следующей отправке");
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Фаза минимальная: останавливаемся последними, когда остальные компоненты уже дописали свои
     * события (пул, воркер etcd). Иначе сброс шёл бы до последнего события механизма.
     */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    /**
     * Открытое событие: пишет итог решения в признаки и закрывается сам.
     *
     * <p>В режиме без отправителя это тот же объект — вызывающий код не ветвится «если наблюдение
     * включено», иначе такую ветку пришлось бы держать в каждом месте вызова.
     */
    public static final class Event implements AutoCloseable {

        private final Span span;

        private Event(Span span) {
            this.span = span;
        }

        public void note(String key, String value) {
            span.setAttribute(key, value);
        }

        public void note(String key, long value) {
            span.setAttribute(key, value);
        }

        public void note(String key, boolean value) {
            span.setAttribute(key, value);
        }

        /** Сбой события: попадает и в трассу, и в журнал (у них общий идентификатор). */
        public void failure(Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getClass().getSimpleName());
        }

        @Override
        public void close() {
            span.end();
        }
    }

    /** Тело события, которому разрешено бросать: записи etcd и пула это умеют. */
    @FunctionalInterface
    public interface ThrowingFunction<A, T> {
        T apply(A argument) throws Exception;
    }

    /** Тело события без результата, которому разрешено бросать. */
    @FunctionalInterface
    public interface ThrowingConsumer<A> {
        void accept(A argument) throws Exception;
    }

    /** Тело события без результата и без span, которому разрешено бросать. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}