# Tasks

## 1. Поверхность ссылок

- [x] 1.1 Выписать всё, что ссылается на старые имена вне исходников: `pom.xml`,
  `application.yml`, Dockerfile'ы, compose, Makefile, скрипты, документация. Зафиксировать,
  что в Dockerfiles только glob по `artifactId` (`target/pool-service-*.jar`), а не по пакету.
- [x] 1.2 Убедиться, что `@ComponentScan`/`scanBasePackages`/`@SpringBootApplication` не заданы
  явно: иначе переименование потребовало бы правки базы сканирования. Импорты проверить на
  отсутствие звёздчатых — иначе подстановка имён неполна.

## 2. Переименование

- [x] 2.1 Перенести каталоги `service/src/**/com/example/poolsvc` → `.../com/abatalev/demo/etcdhikari/service`
  (main и test), заменив `package`/`import` в 25 файлах
- [x] 2.2 То же для `provisioner`: `com/example/provisioner` → `com/abatalev/demo/etcdhikari/provisor`,
  17 файлов
- [x] 2.3 То же для `loadgen`: `ru/hikari/loadgen` → `com/abatalev/demo/etcdhikari/loadgen`,
  3 файла
- [x] 2.4 `logging.level` в `service/src/main/resources/application.yml` и
  `provisioner/src/main/resources/application.yml` — **не пропустить**: при старом имени
  уровень не применится и логирование молча уедет на дефолт, без всякой ошибки
- [x] 2.5 `mainClass` в `loadgen/pom.xml`; `groupId` `com.example` → `com.abatalev.demo.etcdhikari`
  во всех трёх pom'ах (плейсхолдер, противоречащий новому пространству; `artifactId` не
  трогаем — от него зависят glob'ы в Dockerfiles и имена образов)

## 3. Проверка

- [x] 3.1 `mvn test` в `service`, `provisioner`, `loadgen` — все компилируются и проходят
- [x] 3.2 `grep -rn 'com\.example\|ru\.hikari\|poolsvc'` по рабочему дереву — старых имён
  не осталось вне архивов openspec
- [x] 3.3 `docker compose build` — образы собираются (значит fat-jar переупакован с новым
  `mainClass`)
- [x] 3.4 Поднять стенд: инстансы и провизёры healthy, гейт трафика открыт, `/api/pool` и
  `/api/config` отвечают, бюджет в etcd разошёлся теми же долями
- [x] 3.5 `make test` зелёный, `make targets` без недоступных целей