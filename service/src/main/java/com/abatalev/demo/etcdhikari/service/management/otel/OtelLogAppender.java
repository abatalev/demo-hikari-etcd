package com.abatalev.demo.etcdhikari.service.management.otel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.time.Instant;

/**
 * Подписка Logback на те же события журналирования, что уходит в консоль: событие превращается
 * в запись журнала OTel и уходит в точку приёма отправкой (otel-collector → Loki).
 *
 * <p>Почему своя подписка, а не готовая: Boot собирает {@code SdkLoggerProvider} и отправитель
 * OTLP, но Logback к ним не подключает — моста между ними в Boot 3.5 нет. Готовый мост OpenTelemetry
 * ({@code io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0}) читает провайдер из
 * {@code GlobalOpenTelemetry}, а Boot глобальный {@code OpenTelemetry} не заводит: в Boot 3.5 такой
 * код есть только в автоконфигурации, помеченной на удаление, и в 3.5 её уже нет. Своя подписка берёт
 * провайдер у бина и ничего глобального не создаёт.
 *
 * <p>Записи не теряются, если провайдер журналов не собран (точка приёма выключена): тогда
 * {@link #emit} ничего не делает, а консольный вывод от этого не меняется.
 *
 * <p>Запись внутри трассы получает {@code trace_id} и {@code span_id} — по ним в Loki журнал
 * связывается с трассой в Tempo без лишних меток на индексе: это разбираемые атрибуты, а не признаки.
 */
public class OtelLogAppender extends AppenderBase<ILoggingEvent> {

    /** Признак потока: нужен, чтобы отличать потоки друг от друга (в тексте это [%thread]). */
    private static final String THREAD_NAME = "thread.name";

    /** Имя логгера: в Loki это признак потока поиска, по нему ищут по классу. */
    private static final String LOGGER_NAME = "logger";

    private static final String TRACE_ID = "trace_id";
    private static final String SPAN_ID = "span_id";
    private static final String EXCEPTION_TYPE = "exception.type";
    private static final String EXCEPTION_MESSAGE = "exception.message";
    private static final String EXCEPTION_STACKTRACE = "exception.stacktrace";

    private final LoggerProvider loggerProvider;

    public OtelLogAppender(LoggerProvider loggerProvider) {
        this.loggerProvider = loggerProvider;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) {
            return;
        }
        map(loggerProvider.get(event.getLoggerName()), event,
                Span.current().getSpanContext()).emit();
    }

    /**
     * Раскладывает событие журналирования в поля записи OTel. Вынесено отдельно от отправки,
     * чтобы правила переноса проверялись без сети и без точки приёма.
     *
     * @param logger логгер провайдера журналов
     * @param event событие Logback
     * @param spanContext контекст трассы на момент записи; невалидный — запись без связки
     * @return запись, готовая к отправке
     */
    static LogRecordBuilder map(Logger logger, ILoggingEvent event, SpanContext spanContext) {
        LogRecordBuilder record = logger.logRecordBuilder()
                .setTimestamp(Instant.ofEpochMilli(event.getTimeStamp()))
                .setSeverity(severity(event.getLevel()))
                .setSeverityText(event.getLevel().toString())
                .setBody(event.getFormattedMessage());

        AttributesBuilder attributes = Attributes.builder()
                .put(LOGGER_NAME, event.getLoggerName())
                .put(THREAD_NAME, event.getThreadName());
        if (spanContext.isValid()) {
            attributes.put(TRACE_ID, spanContext.getTraceId())
                    .put(SPAN_ID, spanContext.getSpanId());
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            attributes.put(EXCEPTION_TYPE, thrown.getClassName())
                    .put(EXCEPTION_MESSAGE, thrown.getMessage() == null ? "" : thrown.getMessage())
                    .put(EXCEPTION_STACKTRACE, ThrowableProxyUtil.asString(thrown));
        }
        return record.setAllAttributes(attributes.build());
    }

    /** Уровни Logback и OTel совпадают по именам; неизвестный уровень не ломает отправку. */
    static Severity severity(Level level) {
        try {
            return Severity.valueOf(level.toString());
        } catch (IllegalArgumentException e) {
            return Severity.UNDEFINED_SEVERITY_NUMBER;
        }
    }
}
