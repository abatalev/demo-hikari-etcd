# Design

## Context

См. proposal.md (мотивация). Исходное состояние: `EtcdPoolConfigSource` — единственный
`@Component` источника конфигурации, совмещает три цикла (конфиг-watch, регистрация на аренде,
публикация долга) и всю сопутствующую логику гейта/статуса. Класс тестами не покрыт (покрыты
только чистые функции `EtcdKeys`/`EtcdKeyPath`/`DrainDebtPolicy`/`PoolSize`), поэтому
безопасность рефакторинга доказывается неизменностью публичного контракта и проверкой на живом
стенде.

Проверенные ограничения контракта:

- `PoolGauges` ссылается на вложенный тип `EtcdPoolConfigSource.NotReadyCause`;
- `PoolGaugesTest` конструирует фасад сигнатурой `(ManagedPool, EtcdProperties,
  ApplicationEventPublisher, PoolCounters, MechanismSpans)`;
- потребители: `PoolGauges` (`isEnabled/isConnected/isWatchActive/isTrafficAllowed/notReadyCause/
  status()`), `TrafficGateFilter` (`isTrafficAllowed/notReadyReason`), `EtcdConfigHealthIndicator`
  (`isEnabled/isTrafficAllowed/notReadyReason/path`).
- Потоки называются `etcd-config-watch`, `etcd-registration`, `etcd-drain-publication`.

## Goals / Non-Goals

**Goals:**

- Разделить один класс на четыре файла того же пакета с явными границами ответственности.
- Свести к нулю изменения публичного контракта фасада и потребителей; не менять имена потоков,
  последовательность `stop()`, формулы и инварианты AGENTS.md.
- Сделать инварианты структурными: воркер не видит `nodePath`/`unreleasedPath`, публикатор не
  имеет доступа к `apply()`.

**Non-Goals:**

- Не менять поведение, метрики, API, доки и спеки (`skip_specs: true`).
- Не покрывать новый код юнит-тестами (нет Testcontainers; проверка — живой стенд).
- Не трогать `ManagedPool`, `EtcdKeys`, `EtcdKeyPath`, `DrainDebtPolicy`, `PoolBeansConfiguration`.

## Decisions

### 1. Форма разделения: фасад + три коллаборатора

`etcd/EtcdPoolConfigSource.java` остаётся `@Component`-фасадом; циклы и состояния переезжают:

- `EtcdConfigWorker` — снимок + watch (`revision + 1`), применение, гейт трафика, признаки
  источника; владеет `keys/reportedProblems/warnedUnknownKeys/connected/trafficAllowed/...`
  и методами `snapshot/watch/onEvent/apply/updateReadiness/refreshNotReadyReason/
  reportProblems/warnAboutUnknownKeys/status`.
- `InstanceRegistration` — grant → put → keepalive → revoke; владеет логикой `registrationLoop`.
- `UnreleasedShrinkPublisher` — `publicationLoop` + `publishDebt` (по `DrainDebtPolicy`).
- `EtcdShared` (пакетный класс) — общее состояние: `AtomicBoolean running`,
  `AtomicLong registrationLease`, `volatile Client client`.

Альтернатива: оставить один класс, но выделить методы — отвергнуто, цель именно структурные
границы инвариантов.

### 2. Клиент живёт в `EtcdShared` с двумя способами доступа

`clientOrCreate(endpoints, path)` — ленивое создание с `synchronized` (то, что сейчас `client()`
в фасаде, включая строку лога «etcd watch стартует»). `currentClient()` — nullable-чтение поля
без создания. Различие обязано сохраниться: `publishDebt` и `revokeRegistration` сегодня
намеренно **не создают** клиент раньше времени (`if (c == null) return false`), чтобы
публикация/отзыв аренды не поднимали соединение вхолостую. Воркер и регистрация используют
`clientOrCreate`, публикатор и отзыв — `currentClient`.

### 3. Потоки создаёт фасад, тела циклов — коллабораторы

В `start()` фасад по-прежнему создаёт три потока с теми же именами и флагами
(`Thread.ofPlatform().daemon(true).name(...).unstarted(...)`), но целями становятся
`worker::loop`, `registration::loop`, `publisher::loop`. Так `start()`/`stop()` сохраняют
прежнюю форму, а тела циклов живут рядом со своим состоянием.

### 4. `stop()`-последовательность не меняется ни на шаг

Порядок (инвариант «сначала дренаж, потом revoke»):

```
running=false → interrupt всех трёх → join watcher → pool.close() (дренаж)
→ registration.revoke() → join registration → registration.revoke() ещё раз
→ client.close()
```

Двойной отзыв ловит аренду, выданную потоком регистрации между первым отзывом и остановкой
потока. Публикационный поток не джойнится (умирает по interrupt) — как сейчас.

### 5. Статус-типы остаются вложенными в фасаде

`EtcdStatus` и `NotReadyCause` остаются вложенными в `EtcdPoolConfigSource`, воркер строит
`EtcdPoolConfigSource.EtcdStatus` (пакетные ссылки допустимы). Причина — `PoolGauges` ссылается
на `EtcdPoolConfigSource.NotReadyCause`, и перенос типа заставил бы править потребителя, что
противоречит цели «нулевые изменения потребителей». Циклическая ссылка фасад→воркер (по типу)
компилируется и ограничена пакетом; циклов конструирования нет.

### 6. Источник `AvailabilityChangeEvent` меняется с фасада на воркер

Раньше события гейта публиковались с `this` = фасад; теперь их публикует воркер со своим
`this`. Поведение не меняется: Spring Boot записывает готовность по типу состояния
(`ReadinessState`), источник события не участвует. Помечено для проверки на стенде (readiness
группа открывается/закрывается как раньше).

### 7. Публичный контракт фасада и конструктор — как есть

Методы `isEnabled/path/notReadyReason/notReadyCause/isWatchActive/isConnected/isTrafficAllowed/
status/start/stop/isRunning/getPhase` делегируют коллабораторам. Конструктор
`(ManagedPool, EtcdProperties, ApplicationEventPublisher, PoolCounters, MechanismSpans)`
сохраняется — его использует тест `PoolGaugesTest`.

## Risks / Trade-offs

- [Рефакторинг без юнит-тестов на класс]
  → Публичный контракт и конструктор не меняются; неизменность поведения доказывается живым
  стендом (список в tasks.md). Откат — revert одной коммитной единицы.
- [Циклическая ссылка воркер → фасад по вложенным типам]
  → Ограничена пакетом, циклов конструирования нет; плата за нулевые правки в `PoolGauges`.
- [Ошибка переноса: потерять различие `clientOrCreate`/`currentClient`]
  → Публикатор и `revoke()` используют только `currentClient()`; это зафиксировано javadoc и
  проверяется на стенде (публикация долга при снятии конфигурации и отзыв аренды в `stop()`).
- [Источник события гейта сменился (фасад → воркер)]
  → Поведение не зависит от источника (Boot трекает по типу состояния); проверка —
  закрытие/открытие гейта на живом стенде.