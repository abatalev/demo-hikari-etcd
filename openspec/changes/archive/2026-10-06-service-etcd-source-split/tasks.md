# Tasks

## 1. Общий контекст и воркер конфигурации

- [x] 1.1 Создать `etcd/EtcdShared.java`: `AtomicBoolean running`, `AtomicLong registrationLease`,
      `volatile Client client`, `synchronized clientOrCreate(endpoints, path)` (ленивое создание
      с прежней строкой лога) и `currentClient()` (nullable-чтение без создания); verify:
      пакетные классы `etcd` компилируются, поля и методы доступны коллабораторам.
- [x] 1.2 Создать `etcd/EtcdConfigWorker.java` с циклом watch (`loop()`), методами
      `snapshot/watch/onEvent/apply/updateReadiness/refreshNotReadyReason/reportProblems/
      warnAboutUnknownKeys/status` и всем состоянием воркера (`keys/reportedProblems/
      warnedUnknownKeys/connected/trafficAllowed/lastRevision/lastEventAt/applyCount/lastError/
      notReadyReason/notReadyCause/lastOutcome/emptyPrefixLogged`), перенеся их из
      `EtcdPoolConfigSource` без изменений логики (бэкофф 1→30с, `revision + 1`, гейт только по
      реальным событиям, `MASKED_KEYS`, пути префикса `hikari/`); verify: воркер использует
      только `clientOrCreate`, не знает о `nodePath`/`unreleasedPath`, компилируется.
- [x] 1.3 Перенести в воркер построение статуса: `status()` возвращает
      `EtcdPoolConfigSource.EtcdStatus` (вложенный тип фасада), воркер получает
      `properties/path` и собирает `enabled/endpoints/path/keys/problems/revision/lastEventAt/
      lastError/applyCount/lastOutcome/notReadyReason`; verify: набор полей статуса совпадает с
      прежним (сравнить с текущим `status()` до правок).

## 2. Регистрация инстанса

- [x] 2.1 Создать `etcd/InstanceRegistration.java`: `loop()` = grant → put узла → keepalive
      (TTL/3) → перерегистрация с бэкоффом, `revoke()` = отзыв аренды (используя
      `shared.registrationLease` и `currentClient()`, без создания клиента); verify: перенесённые
      тела `registrationLoop`/`revokeRegistration` идентичны исходным по поведению, лог-строки
      сохранены, класс компилируется.

## 3. Публикатор неосвобождённого сжатия

- [x] 3.1 Создать `etcd/UnreleasedShrinkPublisher.java`: `loop()` и `publishDebt()` переносятся из
      `EtcdPoolConfigSource` без изменений (ожидание смены конфигурации через
      `pool.awaitAppliedChange`, `DrainDebtPolicy.toPublish`, запись по аренде узла, спаны
      `etcd.drain_debt.publish`); verify: публикатор использует только `currentClient()` (клиент
      не создаётся вхолостую), терминальный ноль и смена аренды публикуются как раньше,
      компилируется.

## 4. Фасад

- [x] 4.1 Переписать `etcd/EtcdPoolConfigSource.java` в фасад: сохранить сигнатуру конструктора
      `(ManagedPool, EtcdProperties, ApplicationEventPublisher, PoolCounters, MechanismSpans)`,
      вложенные `EtcdStatus`/`NotReadyCause`, публичные методы
      `isEnabled/path/notReadyReason/notReadyCause/isWatchActive/isConnected/isTrafficAllowed/
      status/start/stop/isRunning/getPhase`; фасад строит `EtcdShared` и коллабораторов
      (раскладывая `path/nodePath/unreleasedPath` через `EtcdKeyPath`), создаёт три потока с
      прежними именами (`etcd-config-watch/etcd-registration/etcd-drain-publication`), целями —
      `worker::loop/registration::loop/publisher::loop`; verify: `start()/stop()` сохраняют
      последовательность (interrupt всех → join watcher → `pool.close()` → revoke → join
      registration → revoke → close client) и двойной отзыв аренды.
- [x] 4.2 Собрать модуль и прогнать юнит-тесты: `mvn test` в `service/` (94/94 зелёных, включая
      `PoolGaugesTest` с прежней сигнатурой конструктора фасада); verify: сборка проходит,
      тесты не правятся.

## 5. Интеграционная проверка

- [x] 5.1 `make test` целиком (юнит-тесты трёх модулей + promtool `check config`/`check rules`/
      `test rules`); verify: всё зелёное, конфигурация правил не зависит от изменения.
- [x] 5.2 `make up` на чистом стенде: 8 инстансов готовы, обе точки входа обслуживают
      `/actuator/prometheus`; verify: `docker compose ps` без `RestartCount` > 0 у сервисов.
- [x] 5.3 Состояние и распределение: `make pool` (ready=UP, keys=1 у всех), `make budget`
      (доли 25×4 при N=100, сумма = N), `make registrations` (8 узлов); verify: цифры совпадают с
      ожидаемыми.
- [x] 5.4 Переход группы холодно→тепло: `make set-group-active G=group-2 ACTIVE=false` затем
      `ACTIVE=true`; verify: при R=0 инстансы группы теряют ключи (503, readyness DOWN),
      при возврате — ключи возвращаются, гейт открывается, пул восстанавливается до доли;
      `make pool` показывает переход без ошибок.
- [x] 5.5 Смена бюджета: `make set-active-max-connections SIZE=80 S=service-a` (и обратно 100);
      verify: `make pool`/`make budget` показывают ресайз без пересоздания пула (поколение
      `pool_generation` не растёт), публикация долга укладывает флот в потолок базы.
- [x] 5.6 Остановка/старт инстанса: `docker stop <hex>` одного инстанса и повторный подъём;
      verify: узел регистрации исчезает (revoke после дренажа), при старте регистрируется заново,
      `make instances`/`make registrations` сходятся, правила молчания не шумят.
- [x] 5.7 Обрыв etcd: короткий `docker compose stop etcd`; verify: процесс жив, `pool_etcd_connected`
      = 0, гейт держит последнее состояние (нет 503 на живом пуле), после возврата etcd
      переподключается (watch `revision + 1`), пул не пересоздавался.
- [x] 5.8 Обновить при необходимости комментарии/лог-строки ссылок на `EtcdPoolConfigSource` в
      `PoolSize.java` и javadoc (только если упоминания стали неточными); verify: `git diff`
      не содержит изменений поведения.