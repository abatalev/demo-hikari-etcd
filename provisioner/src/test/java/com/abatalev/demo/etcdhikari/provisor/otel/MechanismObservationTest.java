package com.abatalev.demo.etcdhikari.provisor.otel;

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
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

/**
 * События механизма на стандартном пути Observation — со стороны провизёра.
 *
 * <p>Со второй стороны механизма те же свойства, что у сервиса (см. одноимённый тест в
 * {@code service}), и проверяются отдельно: провизёр пишет в etcd от имени узлов регистрации, и
 * отменённое событие здесь — это не потерянная трасса, а ненаписанная доля.
 *
 * <p>Ключевое свойство моста {@code micrometer-tracing-bridge-otel}: признаки, добавленные в
 * контекст наблюдения <em>после</em> {@code start()}, попадают в готовый спан. На нём держится
 * событие пересчёта флота: «запрошено» пишется до старта, «что решил провизёр» — по дороге, в том
 * же событии.
 */
class MechanismObservationTest {

    /** Мост Observation → OTel, как в Boot; экспортёр в памяти, без сети. */
    private static final class Bridge {

        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        final ObservationRegistry registry = ObservationRegistry.create();

        Bridge() {
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
            OtelTracer tracer =
                    new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), ignored -> {
                    });
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
        }
    }

    @Test
    void attributeAddedAfterStartReachesFinishedSpan() {
        Bridge bridge = new Bridge();

        Observation observation = Observation.createNotStarted("provisioner.fleet.recompute", bridge.registry)
                .lowCardinalityKeyValue(KeyValue.of("service", "service-a"));
        observation.start();
        observation.getContext().addLowCardinalityKeyValue(KeyValue.of("fleet.outcome", "применено"));
        observation.stop();

        assertThat(bridge.exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("provisioner.fleet.recompute");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("service"))).isEqualTo("service-a");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("fleet.outcome"))).isEqualTo("применено");
    }

    @Test
    void recomputeWritesAttributesAndEnds() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        int resizes = spans.callQuietly("provisioner.fleet.recompute",
                o -> o.lowCardinalityKeyValue(KeyValue.of("service", "service-a")),
                event -> {
                    event.note("fleet.resizes", 3);
                    return 3;
                });

        assertThat(resizes).isEqualTo(3);
        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("provisioner.fleet.recompute");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("service"))).isEqualTo("service-a");
        // Признак, добавленный после start() (тело события), мост доносит до готового спана.
        assertThat(span.getAttributes().get(AttributeKey.stringKey("fleet.resizes"))).isEqualTo("3");
        assertThat(span.hasEnded()).isTrue();
    }

    @Test
    void provisionerFailureGoesToTraceAndOutside() {
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        assertThatThrownBy(() -> spans.run("etcd.fleet.write.ceiling", o -> {
        }, () -> {
            throw new IllegalStateException("etcd недоступен");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(bridge.exporter.getFinishedSpanItems().get(0).getStatus().getStatusCode())
                .isEqualTo(StatusCode.ERROR);
    }

    @Test
    void openEventFailureMarksErrorAndPropagates() {
        // Паттерн пересчёта флота: открытое событие, тело в inner try/catch, ERROR до close().
        // Обратный порядок (event.failure(e) в catch у самого try-with-resources) не сработал бы:
        // ресурс закрывается раньше catch, и у завершённого спана статус уже не изменить.
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        assertThatThrownBy(() -> {
            try (MechanismObservation.Event event = spans.start("provisioner.fleet.recompute", o -> {
            })) {
                try {
                    throw new IllegalStateException("etcd недоступен");
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
        // Регрессия с другой стороны механизма: лямбда признаков исполняется до тела события, поэтому
        // ошибка в ней отменяла бы саму запись в etcd. Признак теряется, тело отрабатывает.
        Bridge bridge = new Bridge();
        MechanismObservation spans = new MechanismObservation(bridge.registry);

        Integer unset = null;
        String outcome = spans.callQuietly("provisioner.fleet.recompute",
                o -> o.lowCardinalityKeyValue(
                        KeyValue.of("fleet.full", Integer.toString(unset.intValue()))),
                event -> "пересчитано");

        assertThat(outcome).isEqualTo("пересчитано");
        SpanData span = bridge.exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("provisioner.fleet.recompute");
    }

    @Test
    void noopIsSafe() {
        MechanismObservation noop = MechanismObservation.NOOP;
        noop.event("provisioner.election", o -> o.lowCardinalityKeyValue(KeyValue.of("service", "service-a")));
        noop.callQuietly("provisioner.fleet.recompute", o -> {
        }, event -> null);
    }
}