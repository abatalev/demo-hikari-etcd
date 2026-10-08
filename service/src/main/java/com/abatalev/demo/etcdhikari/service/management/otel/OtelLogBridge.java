package com.abatalev.demo.etcdhikari.service.management.otel;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.logs.LoggerProvider;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Подключает подписку журналов к корневому логгеру Logback.
 *
 * <p>Подписку нельзя объявить в {@code logback-spring.xml}: там доступны только свойства
 * окружения, а провайдер журналов OTel — это бин, который собирает Boot. Поэтому подписка
 * вешается на корневой логгер отсюда, а логгеры, объявленные в конфигурации Logback, её не теряют:
 * добавление подписки к корневому логгеру не трогает уже настроенные уровни и вывод в консоль.
 *
 * <p>Записи до старта этого компонента (само поднятие Spring) в точку приёма не уходят — на них
 * приёма ещё нет. Это осознанно: точка приёма наблюдения не должна влиять на поведение процесса,
 * а потерянные при поднятии строки не влияют ни на что, кроме полноты журнала за первую секунду.
 *
 * <p>Остановка — до остановки приложения ({@link SmartLifecycle} с последней фазой), чтобы
 * компоненты, которые ещё пишут в журнал при завершении, не теряли последние записи.
 */
@Component
class OtelLogBridge implements SmartLifecycle {

    private final LoggerProvider loggerProvider;
    private final OtelLogAppender appender;
    private volatile boolean running;

    OtelLogBridge(LoggerProvider loggerProvider) {
        this.loggerProvider = loggerProvider;
        this.appender = new OtelLogAppender(loggerProvider);
    }

    @Override
    public void start() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender.setContext(context);
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        running = true;
    }

    @Override
    public void stop() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Фаза максимальная: стартует последним и останавливается первым. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
