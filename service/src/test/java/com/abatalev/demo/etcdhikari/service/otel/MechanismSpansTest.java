package com.abatalev.demo.etcdhikari.service.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Свойства событий механизма, на которых держится идея отдельного трассировщика.
 *
 * <p>Главное здесь — отбор: событие механизма обязано попасть в хранилище трасс тогда, когда трасса
 * обслуживания в него не попадает. Это и есть причина, по которой события не идут общим
 * трассировщиком, поэтому свойство проверяется само по себе, а не «на глаз» на стенде.
 */
class MechanismSpansTest {

    /** Идентификаторы выдуманной трассы обслуживания, которая в хранилище не попала. */
    private static final String SERVING_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SERVING_SPAN_ID = "b7ad6b7169203331";

    private final CapturingExporter exporter = new CapturingExporter();
    private final MechanismSpans spans = new MechanismSpans(List.of(exporter),
            Resource.create(Attributes.builder()
                    .put("service.name", "pool-service")
                    .put("node", "service-a-group-1-1")
                    .build()));

    @Test
    void событие_пишется_с_именем_и_признаками() {
        spans.event("etcd.drain_debt.publish", b -> b.setAttribute("etcd.value", 3));
        spans.flush();

        assertThat(exporter.spans).hasSize(1);
        SpanData span = exporter.spans.get(0);
        assertThat(span.getName()).isEqualTo("etcd.drain_debt.publish");
        assertThat(span.getAttributes().get(AttributeKey.longKey("etcd.value"))).isEqualTo(3L);
        // Ресурс приложения общий: без признаков инстанса событие механизма не нашлось бы в Tempo.
        assertThat(span.getResource().getAttribute(AttributeKey.stringKey("node")))
                .isEqualTo("service-a-group-1-1");
    }

    @Test
    void тело_пишет_результат_и_время() throws Exception {
        String result = spans.call("provisioner.fleet.recompute",
                b -> b.setAttribute("service", "service-a"),
                span -> {
                    span.setAttribute("fleet.resizes", 2);
                    return "готово";
                });
        spans.flush();

        assertThat(result).isEqualTo("готово");
        SpanData span = exporter.spans.get(0);
        assertThat(span.getAttributes().get(AttributeKey.longKey("fleet.resizes"))).isEqualTo(2L);
        assertThat(span.hasEnded()).isTrue();
    }

    @Test
    void событие_не_зависит_от_отбора_обслуживания() {
        // Родитель — трасса обслуживания, отобранная и потому в хранилище не попавшая. Событие
        // механизма всё равно уходит, но своей трассой: продолжением неотобранной трассы оно
        // выглядело бы потерянным, а не отдельным следом.
        SpanContext notSampled = SpanContext.createFromRemoteParent(SERVING_TRACE_ID, SERVING_SPAN_ID,
                TraceFlags.getDefault(), TraceState.getDefault());
        try (Scope scope = Span.wrap(notSampled).makeCurrent()) {
            spans.event("pool.config.apply", b -> b.setAttribute("pool.max", 25));
        }
        spans.flush();

        assertThat(exporter.spans).hasSize(1);
        SpanData span = exporter.spans.get(0);
        assertThat(span.getTraceId()).isNotEqualTo(SERVING_TRACE_ID);
        assertThat(span.getParentSpanId()).isEqualTo("0000000000000000");
        // Связь с обслуживанием сохраняется ссылкой: по ней находится журнал того же события.
        assertThat(span.getLinks()).anyMatch(link -> SERVING_TRACE_ID.equals(link.getSpanContext().getTraceId()));
        assertThat(span.getAttributes().get(AttributeKey.stringKey("serving.trace_id")))
                .isEqualTo(SERVING_TRACE_ID);
    }

    @Test
    void вне_обслуживания_ссылок_нет() {
        spans.event("provisioner.election", b -> b.setAttribute("service", "service-a"));
        spans.flush();

        assertThat(exporter.spans.get(0).getLinks()).isEmpty();
        assertThat(exporter.spans.get(0).getAttributes().get(AttributeKey.stringKey("serving.trace_id"))).isNull();
    }

    @Test
    void сбой_попадает_в_трассу_и_идёт_наружу() {
        assertThatThrownBy(() -> spans.run("etcd.put", b -> { }, () -> {
            throw new IllegalStateException("etcd недоступен");
        })).isInstanceOf(IllegalStateException.class);
        spans.flush();

        assertThat(exporter.spans).hasSize(1);
        SpanData span = exporter.spans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> "exception".equals(e.getName()));
        assertThat(span.hasEnded()).isTrue();
    }

    @Test
    void тихий_вариант_не_проглатывает_ошибку_пула() {
        assertThatThrownBy(() -> spans.runQuietly("pool.create", b -> { }, () -> {
            throw new IllegalStateException("пул не создался");
        })).isInstanceOf(IllegalStateException.class);
        spans.flush();

        assertThat(exporter.spans).hasSize(1);
        assertThat(exporter.spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void упавшие_признаки_не_отменяют_тело_события() {
        // Регрессия, найденная на стенде: лямбда признаков исполняется до тела события, и разыменование
        // nullable-поля настроек в признаке отменяло применение конфигурации целиком — пул не создавался,
        // все восемь инстансов стояли с 503. Признак — наблюдение, а не механизм: теряется признак,
        // тело отрабатывает, само событие уходит в хранилище трасс.
        Integer unset = null;
        String outcome = spans.callQuietly("pool.config.apply",
                b -> b.setAttribute("pool.min_idle.requested", unset.intValue()),
                span -> "применено");
        spans.flush();

        assertThat(outcome).isEqualTo("применено");
        assertThat(exporter.spans).hasSize(1);
        assertThat(exporter.spans.get(0).getName()).isEqualTo("pool.config.apply");
        assertThat(exporter.spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void без_отправителя_вызовы_безопасны() {
        MechanismSpans none = new MechanismSpans(List.of(), Resource.empty());
        none.event("pool.create", b -> b.setAttribute("pool.max", 25));
        none.runQuietly("pool.recreate", b -> { }, () -> { });
        none.flush();
    }

    /** Отправитель-заглушка: события mechanism пишутся в список, сеть не нужна. */
    private static final class CapturingExporter implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}