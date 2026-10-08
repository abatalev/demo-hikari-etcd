package com.abatalev.demo.etcdhikari.service.management.otel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Мост журналов: событие Logback превращается в запись OTel с уровнем, текстом, именем логгера,
 * именем потока и, если запись сделана внутри трассы, её идентификаторами.
 *
 * <p>Проверяется переносом, а не отправкой: сама точка приёма и её хранилище проверяются на стенде.
 */
class OtelLogAppenderTest {

    private final List<LogRecordData> exported = new ArrayList<>();

    @Test
    @DisplayName("запись внутри трассы несёт trace_id и span_id, уровень и текст переносятся как есть")
    void recordCarriesTraceAndLevel() {
        SdkLoggerProvider provider = provider();
        ILoggingEvent event = event(Level.INFO, "пул обновлён на лету: max=4", null);
        SpanContext span = SpanContext.create("0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());

        try (Scope scope = Context.current().with(Span.wrap(span)).makeCurrent()) {
            started(provider).doAppend(event);
        }
        assertThat(exported).hasSize(1);
        LogRecordData record = exported.get(0);
        assertThat(record.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(record.getSeverityText()).isEqualTo("INFO");
        assertThat(record.getBody().asString()).isEqualTo("пул обновлён на лету: max=4");
        assertThat(string(record, "trace_id")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(string(record, "span_id")).isEqualTo("b7ad6b7169203331");
        assertThat(string(record, "logger")).isEqualTo("com.abatalev.demo.etcdhikari.service.management.pool.ManagedPool");
        assertThat(string(record, "thread.name")).isEqualTo("etcd-config-watch");
    }

    @Test
    @DisplayName("запись вне трассы не несёт ни trace_id, ни span_id")
    void recordOutsideTraceHasNoTraceIds() {
        SdkLoggerProvider provider = provider();
        ILoggingEvent event = event(Level.WARN, "etcd watch повтор", null);

        started(provider).doAppend(event);

        assertThat(exported).hasSize(1);
        LogRecordData record = exported.get(0);
        assertThat(record.getSeverity()).isEqualTo(Severity.WARN);
        assertThat(record.getAttributes().get(AttributeKey.stringKey("trace_id"))).isNull();
        assertThat(record.getAttributes().get(AttributeKey.stringKey("span_id"))).isNull();
    }

    @Test
    @DisplayName("исключение переносится тремя атрибутами: тип, сообщение и трассировка стека")
    void exceptionBecomesAttributes() {
        SdkLoggerProvider provider = provider();
        ILoggingEvent event = event(Level.ERROR, "/api/work упал", new IllegalStateException("связь оборвалась"));

        started(provider).doAppend(event);

        LogRecordData record = exported.get(0);
        assertThat(string(record, "exception.type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(string(record, "exception.message")).isEqualTo("связь оборвалась");
        assertThat(string(record, "exception.stacktrace")).contains("IllegalStateException");
    }

    @Test
    @DisplayName("незапущенная подписка ничего не отправляет: журналирование не должно зависеть от точки приёма")
    void notStartedAppenderSendsNothing() {
        SdkLoggerProvider provider = provider();
        OtelLogAppender appender = new OtelLogAppender(provider);

        appender.doAppend(event(Level.INFO, "подписка ещё не включена", null));

        assertThat(exported).isEmpty();
    }

    private SdkLoggerProvider provider() {
        LogRecordExporter exporter = new LogRecordExporter() {
            @Override
            public CompletableResultCode export(Collection<LogRecordData> records) {
                exported.addAll(records);
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
        };
        return SdkLoggerProvider.builder()
                .addLogRecordProcessor(io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor.create(exporter))
                .build();
    }

    /** Подписка в бою работает запущенной: как её включает мост, см. OtelLogBridge. */
    private OtelLogAppender started(SdkLoggerProvider provider) {
        OtelLogAppender appender = new OtelLogAppender(provider);
        appender.start();
        return appender;
    }

    private ILoggingEvent event(Level level, String message, Throwable thrown) {
        ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
        Mockito.when(event.getLevel()).thenReturn(level);
        Mockito.when(event.getFormattedMessage()).thenReturn(message);
        Mockito.when(event.getLoggerName()).thenReturn("com.abatalev.demo.etcdhikari.service.management.pool.ManagedPool");
        Mockito.when(event.getThreadName()).thenReturn("etcd-config-watch");
        Mockito.when(event.getTimeStamp()).thenReturn(1_700_000_000_000L);
        Mockito.when(event.getThrowableProxy())
                .thenReturn(thrown == null ? null : new ThrowableProxy(thrown));
        return event;
    }

    private String string(LogRecordData record, String key) {
        return record.getAttributes().get(AttributeKey.stringKey(key));
    }
}
