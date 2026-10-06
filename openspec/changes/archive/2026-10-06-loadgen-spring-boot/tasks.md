# Tasks

> Пакет из п. 1.2 позже переименован на `com.abatalev.demo.etcdhikari.loadgen`
> (изменение `java-package-namespaces`); здесь записано то, что делалось тогда.

## 1. Maven-проект нагрузчика

- [x] 1.1 Создать `loadgen/pom.xml`: самостоятельный проект (корневого агрегатора в проекте нет) на
  `spring-boot-starter-parent` 3.5.16, `java.version` 21, `artifactId` `loadgen`. Проверить, что
  версия совпадает с `service/pom.xml` и `provisioner/pom.xml`
- [x] 1.2 Перенести `LoadGen.java` в `loadgen/src/main/java/ru/hikari/loadgen/LoadGen.java`,
  добавив единственную строку `package ru.hikari.loadgen;` — иначе точка входа не увидит класс
  (`@SpringBootApplication` в default package не работает, default package невидим из named
  package). Проверить: `git show HEAD:loadgen/LoadGen.java | diff - <новый путь>` даёт ровно
  две добавленные строки — декларацию и пустую
- [x] 1.3 Удалить `LoadGen.class` и `LoadGen$Latencies.class` из `loadgen/` (мусор от локального
  `javac`), добавить `loadgen/target/` в `.gitignore`. Проверить `git status --short` — артефактов
  сборки в индексе быть не должно

## 2. Точка входа

- [x] 2.1 Создать `LoadGenApp` (`@SpringBootApplication`): тип приложения `none`, баннер выключен,
  логирование заглушено, стартовые сообщения выключены — иначе они попадут в stdout и смешаются с
  отчётом, который читает `make load-logs`
- [x] 2.2 Реализацию запуска сделать `CommandLineRunner`, вызывающим `LoadGen.main(args)`: контекст
  поднят, приложение живёт ровно столько, сколько работает нагрузчик
- [x] 2.3 Проверить, что завершение по-прежнему печатает итог: shutdown-hook из `LoadGen` должен
  отработать при остановке контейнера

## 3. Сборка и локальный запуск

- [x] 3.1 `mvn -q package` в `loadgen/`: собрать fat-jar. Проверить, что jar создан и запускается
- [x] 3.2 Запустить jar с теми же переменными окружения, что и до перевода, против живого стенда:
  отчёт построчный, колонки те же, первая строка-шапка та же. Сравнить вывод с прогоном до перевода
- [x] 3.3 Проверить обе семантики длительности: `DURATION_S=10` завершает прогон сам, `DURATION_S=0`
  держит процесс до остановки

## 4. Образ и стенд

- [x] 4.1 Переписать сборочную часть `loadgen/Dockerfile` на Maven-сборку, рантайм — `java -jar`;
  `sh -c` и `JAVA_OPTS` оставить (подстановка из окружения). Проверить `docker compose config`
- [x] 4.2 Собрать и поднять обе точки нагрузки (`loadgen-a`, `loadgen-b`) на стенде. Проверить
  `make load-logs` и `make load-logs LOAD_S=service-b`: отчёт идёт в stdout, каждая точка бьёт свою базу
- [x] 4.3 `make stress LOAD_S=service-a` и `make stress LOAD_S=service-b` — прогон проходит, отчёт
  двух прогонов не смешивает две базы. `make build` собирает все образы
- [x] 4.4 Убедиться, что `docker-compose.yml`, `Makefile`, `.env` и `.env.example` не потребовали
  правок: набор переменных окружения нагрузчика неизменен

## 5. Документация

- [x] 5.1 `README.md`: «один файл на голом JDK» → Maven + Spring Boot; поправить описание в
  «Состав» и упоминание про `javac LoadGen.java`, если оно есть
- [x] 5.2 `AGENTS.md`: состав стенда и раздел про нагрузчик — новая сборка; проверить, что
  инвариант «две точки нагрузки, `LoadGen.java` не менялся» сформулирован заново и верен
- [x] 5.3 `docs/operations.md` и `docs/experiments.md`: команды сборки/запуска нагрузчика в
  разделе переменных и в «Как повторить»
- [x] 5.4 Прогнать `make test` и убедиться, что он зелёный; проверить `git status --short` —
  в индексе нет ни `.class`, ни `target/`, ни локального `.env`