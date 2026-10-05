package com.abatalev.demo.etcdhikari.provisor.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Свойства событий механизма у провизёра: отбор всегда, признаки безвредны, тело не отменяется.
 *
 * <p>Со второй стороны механизма те же свойства, что у сервиса (см. одноимённый тест в `service`),
 * и проверяются отдельно: провизёр пишет в etcd от имени узлов регистрации, и отменённое событие
 * здесь — это не потерянная трасса, а ненаписанная доля.
 */
class MechanismSpansTest {

    private final CapturingExporter exporter = new CapturingExporter();
    private final MechanismSpans spans = new MechanismSpans(List.of(exporter),
            Resource.create(Attributes.builder()
                    .put("service.name", "config-provisioner")
                    .put("replica", "provisioner-a")
                    .build()));

    @Test
    void пересчёт_пишется_с_признаками_и_временем() throws Exception {
        int resizes = spans.call("provisioner.fleet.recompute",
                b -> b.setAttribute("service", "service-a"),
                span -> {
                    span.setAttribute("fleet.resizes", 3);
                    return 3;
                });
        spans.flush();

        assertThat(resizes).isEqualTo(3);
        assertThat(exporter.spans).hasSize(1);
        SpanData span = exporter.spans.get(0);
        assertThat(span.getName()).isEqualTo("provisioner.fleet.recompute");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("service"))).isEqualTo("service-a");
        assertThat(span.getAttributes().get(AttributeKey.longKey("fleet.resizes"))).isEqualTo(3L);
        assertThat(span.getResource().getAttribute(AttributeKey.stringKey("replica")))
                .isEqualTo("provisioner-a");
    }

    @Test
    void отказ_провизёра_попадает_в_трассу_и_идёт_наружу() {
        assertThatThrownBy(() -> spans.run("etcd.fleet.write.ceiling", b -> { }, () -> {
            throw new IllegalStateException("etcd недоступен");
        })).isInstanceOf(IllegalStateException.class);
        spans.flush();

        assertThat(exporter.spans).hasSize(1);
        assertThat(exporter.spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void упавшие_признаки_не_отменяют_тело_события() {
        // Регрессия с другой стороны механизма: лямбда признаков исполняется до тела события, поэтому
        // ошибка в ней отменяла бы саму запись в etcd. Признак теряется, тело отрабатывает.
        Integer unset = null;
        String outcome = spans.callQuietly("provisioner.fleet.recompute",
                b -> b.setAttribute("fleet.full", unset.intValue()),
                span -> "пересчитано");
        spans.flush();

        assertThat(outcome).isEqualTo("пересчитано");
        assertThat(exporter.spans).hasSize(1);
        assertThat(exporter.spans.get(0).getName()).isEqualTo("provisioner.fleet.recompute");
    }

    @Test
    void без_отправителя_вызовы_безопасны() {
        MechanismSpans none = new MechanismSpans(List.of(), Resource.empty());
        none.event("provisioner.election", b -> b.setAttribute("service", "service-a"));
        none.callQuietly("provisioner.fleet.recompute", b -> { }, span -> null);
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