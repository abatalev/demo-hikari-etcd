package com.abatalev.demo.etcdhikari.service.management.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

/**
 * Свойства событий механизма на стандартном пути Observation.
 *
 * <p>Вся замена отдельного трассировщика держится на одном свойстве моста
 * {@code micrometer-tracing-bridge-otel}: признаки, добавленные в контекст наблюдения
 * <em>после</em> {@code start()}, попадают в готовый спан. Именно так события с дописанным в теле
 * итогом (применён ли конфиг, сколько соединений осталось после дренажа) остаются одним событием:
 * {@code start(note(...) -> apply()} — это «запрошено», а {@code note(...)} внутри тела — «стало».
 *
 * <p>Отбор и родительство здесь не проверяются — они решаются общим реестром и сэмплером
 * приложения: событие в потоке запроса берёт текущий спан родителем, событие из фонового потока
 * становится корнем своей трассы. Отдельного всегда-on провайдера больше нет.
 */
class MechanismObservationTest {

    /** Мост Observation → OTel, как в Boot; экспортёр в памяти, без сети. */
    private static final class Bridge {

        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        final ObservationRegistry registry = ObservationRegistry.create();
        final SdkTracerProvider provider;
        final io.opentelemetry.api.trace.Tracer otelTracer;

        Bridge() {
            provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
            otelTracer = provider.get("test");
            OtelTracer tracer =
                    new OtelTracer(otelTracer, new OtelCurrentTraceContext(), ignored -> {
                    });
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
        }
    }

    @Test
    void attributeAddedAfterStartReachesFinishedSpan() {
        Bridge bridge = new Bridge();

        Observation observation = Observation.createNotStarted("pool.config.apply", bridge.registry)
                .lowCardinalityKeyValue(KeyValue.of("config.reason", "budget"));
        observation.start();
        observation.getContext().addLowCardinalityKeyValue(KeyValue.of("config.outcome", "RESIZED"));
        observation.stop();

        assertThat(bridge.exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("pool.config.apply");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("config.reason"))).isEqualTo("budget");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("config.outcome"))).isEqualTo("RESIZED");
    }

    @Test
    void eventWrittenWithNameAndAttributes() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        spans.event("etcd.drain_debt.publish",
                o -> o.lowCardinalityKeyValue(KeyValue.of("etcd.value", Integer.toString(3))));

        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("etcd.drain_debt.publish");
        // Значения признаков — строки: key-value Observation не различает типы, число приводится явно.
        assertThat(span.getAttributes().get(AttributeKey.stringKey("etcd.value"))).isEqualTo("3");
    }

    @Test
    void bodyWritesResultAndNoteAfterStart() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        String result = spans.callQuietly("provisioner.fleet.recompute",
                o -> o.lowCardinalityKeyValue(KeyValue.of("service", "service-a")),
                event -> {
                    event.note("fleet.resizes", 2);
                    return "готово";
                });

        assertThat(result).isEqualTo("готово");
        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        // Признак, добавленный после start() (тело события), мост доносит до готового спана.
        assertThat(span.getAttributes().get(AttributeKey.stringKey("fleet.resizes"))).isEqualTo("2");
        assertThat(span.hasEnded()).isTrue();
    }

    @Test
    void openEventNotesAfterStartReachFinishedSpan() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        try (MechanismObservation.Event event = spans.start("provisioner.fleet.recompute",
                o -> o.lowCardinalityKeyValue(KeyValue.of("service", "service-a")))) {
            event.note("fleet.outcome", "применено");
            event.note("fleet.budget", 100);
        }

        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("provisioner.fleet.recompute");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("fleet.outcome"))).isEqualTo("применено");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("fleet.budget"))).isEqualTo("100");
    }

    @Test
    void failureGoesToTraceAndOutside() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        assertThatThrownBy(() -> spans.run("etcd.put", o -> {
        }, () -> {
            throw new IllegalStateException("etcd недоступен");
        })).isInstanceOf(IllegalStateException.class);

        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> "exception".equals(e.getName()));
        assertThat(span.hasEnded()).isTrue();
    }

    @Test
    void openEventFailureMarksErrorAndPropagates() {
        // Паттерн создания пула: открытое событие, тело в inner try/catch, ERROR до close().
        // Обратный порядок (event.failure(e) в catch у самого try-with-resources) не сработал бы:
        // ресурс закрывается раньше catch, и у завершённого спана статус уже не изменить.
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        assertThatThrownBy(() -> {
            try (MechanismObservation.Event event = spans.start("pool.create", o -> {
            })) {
                try {
                    throw new IllegalStateException("пул не создался");
                } catch (RuntimeException | Error e) {
                    event.failure(e);
                    throw e;
                }
            }
        }).isInstanceOf(IllegalStateException.class);

        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.hasEnded()).isTrue();
    }

    @Test
    void failedAttributesDoNotCancelBody() {
        // Регрессия, найденная на стенде: лямбда признаков исполняется до тела события, и разыменование
        // nullable-поля настроек в признаке отменяло применение конфигурации целиком — пул не создавался,
        // все восемь инстансов стояли с 503. Признак — наблюдение, а не механизм: теряется признак,
        // тело отрабатывает, само событие уходит в хранилище трасс.
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        Integer unset = null;
        String outcome = spans.callQuietly("pool.config.apply",
                o -> o.lowCardinalityKeyValue(
                        KeyValue.of("pool.min_idle.requested", Integer.toString(unset.intValue()))),
                event -> "применено");

        assertThat(outcome).isEqualTo("применено");
        assertThat(bridge.exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(bridge.exporter.getFinishedSpanItems().get(0).getName()).isEqualTo("pool.config.apply");
    }

    @Test
    void eventInRequestThreadIsChildOfServingTrace() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        io.opentelemetry.api.trace.Span serving = bridge.otelTracer.spanBuilder("serving").startSpan();
        try (Scope scope = serving.makeCurrent()) {
            spans.event("pool.acquire.wait", o -> o.lowCardinalityKeyValue(KeyValue.of("pool.max", "25")));
        } finally {
            serving.end();
        }

        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("pool.acquire.wait");
        // Событие из потока запроса — часть трассы запроса, а не отдельный след.
        assertThat(span.getTraceId()).isEqualTo(serving.getSpanContext().getTraceId());
        assertThat(span.getParentSpanId()).isEqualTo(serving.getSpanContext().getSpanId());
    }

    @Test
    void eventFromBackgroundThreadIsOwnTrace() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        spans.event("provisioner.fleet.recompute",
                o -> o.lowCardinalityKeyValue(KeyValue.of("service", "service-a")));

        // В фоновом потоке (воркер etcd, публикация долга) текущего спана нет — событие корень трассы.
        assertThat(bridge.exporter.getFinishedSpanItems().get(0).getParentSpanId())
                .isEqualTo("0000000000000000");
    }

    @Test
    void noopIsSafe() throws Exception {
        MechanismObservation noop = MechanismObservation.NOOP;
        noop.event("pool.create", o -> o.lowCardinalityKeyValue(KeyValue.of("pool.max", "25")));
        noop.run("pool.recreate", o -> {
        }, () -> {
        });
        try (MechanismObservation.Event event = noop.start("pool.config.apply", o -> {
        })) {
            event.note("config.outcome", "RESIZED");
            event.failure(new IllegalStateException("не бывает"));
        }
    }
}