package ru.hikari.loadgen;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Запуск нагрузчика при старте приложения.
 *
 * Вызов блокирующий — {@code LoadGen.main} возвращается только когда прогон закончился
 * ({@code DURATION_S} истёк) либо процесс остановлен. Пока он не вернулся, приложение живо,
 * и остановка контейнера приводит к shutdown-hook из {@code LoadGen}, печатающему итог.
 *
 * {@code LoadGen.main} не трогается: правка таймингов нагрузки здесь была бы правкой
 * измеряемой величины.
 */
@Component
class LoadGenRunner implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        LoadGen.main(args);
    }
}