package ru.hikari.loadgen;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка входа нагрузчика: Spring Boot, из которого запускается существующий {@link LoadGen}.
 *
 * Настройки задаются здесь, а не переменными окружения — иначе стенд зависил бы от того,
 * что кто-то правит compose. Всё, что не относится к запуску, живёт в {@code LoadGen}:
 * настройки через env, нагрузка, отчёт в stdout и итог по shutdown-hook.
 */
@SpringBootApplication
public class LoadGenApp {

    public static void main(String[] args) {
        // Отчёт нагрузчика идёт в stdout, и его читает `make load-logs`.
        // Баннер и логи Spring Boot в тот же поток попасть не должны: одна лишняя
        // строка инициализации — визуальный мусор в самом нужном месте.
        System.setProperty("logging.level.root", "OFF");

        SpringApplication app = new SpringApplication(LoadGenApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.run(args);
    }
}