# Tasks

## 1. Провижер: переименование ключей бюджета

- [ ] 1.1 В `InstanceKey` переименовать распознанные сервисные настройки: `MAX_CONNECTIONS` →
  `activeMaxConnections`, `MIN_CONNECTIONS` → `activeMinConnections`, добавить
  `INACTIVE_MAX_CONNECTIONS = "inactiveMaxConnections"`; обновить javadoc грамматики
- [ ] 1.2 В `ProvisionerProperties` переименовать настройки: `PROV_MAX_CONNECTIONS` →
  `PROV_ACTIVE_MAX_CONNECTIONS`, `PROV_MIN_CONNECTIONS` → `PROV_ACTIVE_MIN_CONNECTIONS`, добавить
  `PROV_INACTIVE_MAX_CONNECTIONS` (по умолчанию 1); обновить `application.yml` провизора,
  `docker-compose.yml` и `.env.example`
- [ ] 1.3 Обновить `InstanceKeyTest`: новые имена сервисных настроек распознаются,
  `inactiveMaxConnections` среди них, прежние `maxConnections`/`minConnections` дают `Other`
- [ ] 1.4 Проверить, что переименование не сломало `PoolSizeDistributionTest` (чистая функция места
  размера не меняет); `make test` по модулю провизора зелёный

## 2. Провижер: чистый разбор маркера группы

- [ ] 2.1 Добавить чистый класс `GroupFleetKey`: префикс `{root}/groups/`, ключ маркера
  `{root}/groups/{group}/active`, разбор (пустая группа / неверные сегменты → неверный ключ),
  чтение из снимка префикса в `Map<group, Boolean>` (значение «false» → неактивна, остальное —
  активна); используется для логирования опечаток
- [ ] 2.2 Покрыть `GroupFleetKey` юнит-тестами: разбор валидного ключа, отсутствие маркера →
  активна, значение «true»/мусор → активна, «false» → неактивна, мусорный ключ в префиксе
  не даёт группы

## 3. Провижер: распределение с резервом неактивного флота

- [ ] 3.1 В `ProvisioningWorker` при сканировании поддерева сервиса дополнительно читать маркеры
  групп (`{root}/groups/`) и вместе с узлами строить разбиение «активные / неактивные»
- [ ] 3.2 Реализовать в `apply()`/`recompute()` формулу с резервом: k = число неактивных узлов,
  неактивные — ровно R (put-if-absent прочих ключей, `maximumPoolSize` = R), активные —
  `PoolSizeDistribution.distribute(N − R×k, m, maxShare, активные)`; сумма по сервису = N;
  `minimumIdle`/`connectionTimeoutMs`/прочие ключи — как прежде
- [ ] 3.3 Fail-closed на сервис (WARN с числами, ничего не менять): R не-число или вне
  `{0} ∪ [1, maxShare]`; `R×k > N`; активные есть и `N − R×k < m`
- [ ] 3.4 R = 0: неактивные узлы не получают доли — существующая протирка «узел без доли»
  сносит их конфигурацию целиком
- [ ] 3.5 Обновить лог сверки: числа живых/активных/неактивных, N, R, резерв, сумма долей

## 4. Провижер: поллинг маркера групп

- [ ] 4.1 В `ProvisioningWorker` добавить периодический поллинг префикса `{root}/groups/` (~1с,
  отдельный поток, старт/стоп вместе с воркером): при изменении маркера группы, присутствующей
  в деревьях сервиса (по последнему скану), вызывать `recompute()` (сериализован с watch);
  при обрыве etcd тики пропускаются, состояние маркеров сохраняется (fail-closed)
- [ ] 4.2 Убедиться, что маркеры `{root}/groups/` не попадают в снимок/watch дерева сервиса и
  выборы (`liveServices`), «неизвестных ключей» по ним нет
- [ ] 4.3 Стоп: поллинг останавливается в `stop()` воркера, при потере лидерства поток не течёт

## 5. Сервис: пул только из конфигурации

- [ ] 5.1 `application.yml` сервиса: `maximum-pool-size: 0` (литерал, комментарий «задаётся из
  etcd»); из `docker-compose.yml` и `.env.example` убрать `POOL_MAX_SIZE`
- [ ] 5.2 `HikariSettings.normalize()`: диапазон максимума `[0, POOL_SIZE_MAX]`, при максимуме 0
  минимум следует → 0, предупреждения о сочетаниях при 0 не выдаются; дополнить
  `HikariSettingsTest` кейсами нуля
- [ ] 5.3 `ManagedPool`: конструктор не создаёт пул при стартовом максимуме 0; `apply()`:
  целевой 0 → дренаж + закрытие (`Outcome.CLOSED`), уже закрыт → `UNCHANGED`; 0 → >0 → создание
  с нуля; `settings()`/`runtime()` null-safe (runtime «пула нет»: total 0, closed), REJECTED-путь
  не падает на `applied.get() == null`
- [ ] 5.4 `EtcdPoolConfigSource`: отказ `maximumPoolSize == 0` из etcd до `resolve`
  (`InvalidSettingsException` → REJECTED, причина в статусе, гейт остаётся открытым)
- [ ] 5.5 `PoolController`: `/api/pool` и `/api/config` работают без пула (`settings: null`),
  `/api/work` — по гейту
- [ ] 5.6 `make test` (оба модуля) зелёный

## 6. Стенд: команды и скрипты

- [ ] 6.1 `Makefile`: переименовать `set-max-connections` → `set-active-max-connections`,
  `set-min-connections` → `set-active-min-connections` (новые ключи, валидация как прежде),
  добавить `set-inactive-max-connections` (R ≥ 0) и `set-group-active` (маркер `true|false`)
- [ ] 6.2 `scripts/budget.py`: новые имена ключей, R, маркеры групп, сумма долей
- [ ] 6.3 Проверить `make budget` и новую команду на стенде после `make clean && make up`
  (миграция старых ключей не делается)

## 7. Документация и AGENTS

- [ ] 7.1 `AGENTS.md`: переименования, R-инварианты (0-локальный дефолт, etcd-0 REJECTED, пул
  только из etcd, маркер+поллинг, формула), снятие кейвета «потолок защищает не полностью»
- [ ] 7.2 `README.md`, `docs/mechanism.md`, `docs/operations.md`, `docs/api.md`: новая схема
  ключей, команды, сценарии флота, миграция (make clean && make up)

## 8. Проверка на живом стенде

- [ ] 8.1 `make clean && make up` заново; `make budget`/`make pool`: все 4 инстанса дефолт
  25×4, все ready UP
- [ ] 8.2 `make set-active-max-connections SIZE=100 S=service-a`, затем
  `make set-inactive-max-connections SIZE=1 S=service-a` и `make set-group-active G=group-1
  ACTIVE=false`: узлы group-1 (service-a и service-b) держат 1, активные пересчитаны
  (N − R×k), сумма = N, `make budget` сходится
- [ ] 8.3 Холод: `set-inactive-max-connections SIZE=0` — неактивные инстансы теряют
  конфигурацию, `/api/work` 503, `readiness` DOWN, `liveness` UP; возврат группы — снова ready
- [ ] 8.4 Отказ-замок: R=0 и N−R×k < m / R×k > N — конфигурация не меняется, WARN с числами
- [ ] 8.5 etcd-0: `etcdctl put .../hikari/maximumPoolSize 0` — REJECTED (гейт открыт, пул на
  прежних значениях), причина в `/api/config`
- [ ] 8.6 Сумма долей = N при активном флоте и флоте с неактивной группой; postgres
  `max_connections` не превышен; `make stress` короткий прогон без ошибок
- [ ] 8.7 Финально: стенд возвращён в дефолтное состояние, `make test` зелёный,
  `openspec validate inactive-fleet` валиден