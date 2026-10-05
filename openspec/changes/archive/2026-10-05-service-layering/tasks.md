# Tasks

## 1. Доступ к базе

- [x] 1.1 Завести `dao/DemoItemsDao.java`: `countItems()` (`SELECT count(*) FROM demo_items`) и
  `sleep(long ms)` (`SELECT pg_sleep(?)` с удержанием соединения) — те же два запроса, что были
  инлайнены в контроллере, `@Component`. Проверка: `mvn -q -DskipTests compile` в `service/`.
- [x] 1.2 Обоим методам оставить комментарии из старого контроллера: `pg_sleep` держит коннект
  занятым, `count(*)` отражает размер таблицы, а не нагрузку. Проверка: комментарии на месте,
  текст запросов не изменён.

## 2. Сценарий

- [x] 2.1 Завести `service/WorkService.java`: `run(long ms, boolean countRows)` и вложенная запись
  `Measurement(durationMs, dbMs, queueWaitMs, rows)` без признаков транспорта. Проверка: в записи нет
  `ok` и `error`, `@Component`.
- [x] 2.2 Порядок шагов и границы таймеров — как были: счёт до отметки `dbStart`, сон внутри окна
  `dbMs`, `queueWaitMs = durationMs - dbMs`. Проверка: тест `WorkServiceTest` (задача 3.1).

## 3. HTTP

- [x] 3.1 Написать `WorkServiceTest`: счёт выполняется раньше сна и не выполняется при
  `countRows=false` (Mockito, `InOrder`). Проверка: `mvn -q test -Dtest=WorkServiceTest` зелёный.
- [x] 3.2 Перенести `web/PoolController.java` и `web/TrafficGateFilter.java` в `controller/`,
  переписать пакет и импорты. Проверка: в `service/src/main` нет пакета `web`, компиляция проходит.
- [x] 3.3 Утоньшить контроллер до HTTP: разбор параметров, диапазон `ms` → 400, вызов сценария,
  маппинг в `WorkResponse`, 503 с прежним текстом ошибки и прежней отметкой входа для `durationMs`.
  Проверка: SQL-запросов и подсчёта времён в контроллере не осталось, текст 503 в коде прежний.
- [x] 3.4 Прогнать `make test` целиком: компиляция, юнит-тесты, сверки состава, promtool.
- [x] 3.5 Пометить бин `managedPool` как `@Primary` — нашёлся на живом стенде: бин `dataSource`
  отдаёт тот же пул, поэтому после его создания Spring считает его вторым кандидатом на
  `ManagedPool`, и контекст падал на «found 2: managedPool,dataSource». Разбиение сдвинуло порядок
  регистрации бинов (`DemoItemsDao` тянет `JdbcTemplate`, а значит и `dataSource`) и вскрыло
  латентный дефект, зависевший от порядка. Проверка: стенд поднимается, все восемь инстансов
  `ready=UP`.

## 4. Живая проверка

- [x] 4.1 Поднять стенд (`make up`) и снять ответы: `GET /api/work?ms=50` — тело побайтно как до
  разбиения; 400 вне диапазона `ms`; 503 перегруза под насыщением с текстом
  `CannotGetJdbcConnectionException: Failed to obtain JDBC Connection`; 503 гейта конфигурации с
  прежним телом.
- [x] 4.2 Убедиться, что измерение не поехало: `make stress DURATION_S=10` против baseline
  (`rps≈150, p50=26, p95=27, max=60`). Снято: `rps=149, p50=26, p95=27, max=68, err=0` — расхождение
  по `max` в шум, перцентили совпали, то есть границы измерений не сдвинулись.
- [x] 4.3 `make pool` и `make check-instances` — зелёные, стенд в исходном состоянии.

## 5. Документация и архив

- [x] 5.1 В `README.md` («Состав») заменить строку `web/` строками `controller/`, `service/`,
  `dao/` с указанием, что в них. Проверка: раздел «Состав» совпадает с фактическим деревом пакетов.
- [x] 5.2 Проверить `openspec validate service-layering --strict`, заархивировать изменение и
  убедиться, что `openspec validate --specs` по-прежнему зелёный (спеки не тронуты).