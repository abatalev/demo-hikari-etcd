# Proposal

## Why

`EtcdPoolConfigSource` (808 строк) совмещает три независимые заботы источника конфигурации
инстанса: (1) снимок + watch + применение конфигурации и гейт трафика, (2) регистрацию узла
инстанса на аренде, (3) публикацию неосвобождённого сжатия. Инварианты «регистрация не трогает
конфиг-воркер», «публикация не блокирует watch», «обрыв etcd не влияет на пул» держатся только
на комментариях и именах потоков внутри одного класса; разделение делает их структурными.
Поведение не меняется — это рефакторинг читаемости и стоимости будущих правок.

## What Changes

- Класс `etcd/EtcdPoolConfigSource.java` разделяется на четыре файла того же пакета:
  - `EtcdConfigWorker` — снимок, watch (`revision + 1`), применение конфигурации, гейт трафика,
    признаки источника (ключи, проблемы, ревизия, причина неготовности);
  - `InstanceRegistration` — аренда узла регистрации: grant → put → keepalive → revoke;
  - `UnreleasedShrinkPublisher` — цикл публикации неосвобождённого сжатия и запись по аренде узла;
  - `EtcdPoolConfigSource` — фасад `@Component`: `SmartLifecycle`, ленивый клиент, пути,
    последовательность `stop()` (дренаж → revoke → join), общий статус.
- Общее состояние (флаг `running`, номер аренды, клиент) живёт в небольшом общем контексте
  `EtcdShared`, передаваемом коллабораторам.
- Публичный API фасада не меняется: `isEnabled/path/notReadyReason/notReadyCause/isWatchActive/
  isConnected/isTrafficAllowed/status/start/stop/isRunning/getPhase`, — потребители
  (`PoolGauges`, `TrafficGateFilter`, `EtcdConfigHealthIndicator`) не правятся.
- Имена потоков (`etcd-config-watch`, `etcd-registration`, `etcd-drain-publication`),
  порядок остановки, бэкофф-циклы и все инварианты (см. AGENTS.md) сохраняются без изменений.
- **BREAKING**: нет. Поведение стенда и контракты API/метрик не меняются; `docs/` и спеки не
  правятся (изменение объявляет `skip_specs: true`).

## Capabilities

### New Capabilities

Нет: поведение не меняется, изменение чисто структурное.

### Modified Capabilities

Нет: требования capabilities (`etcd-config-source`, `instance-registration`,
`config-validation`, `pool-observability`, `traffic-readiness`) не затрагиваются.
Опт-аут от спек задан `skip_specs: true` в `.openspec.yaml`.

## Impact

- Изменяется только `service/`: `EtcdPoolConfigSource.java` (переписывается в фасад) и три новых
  класса в `service/src/main/java/com/abatalev/demo/etcdhikari/service/etcd/`. Тестовые файлы не
  затрагиваются (класс тестами не покрыт — покрыты только чистые функции).
- Зависимости, конфигурация, метрики, API и доки не меняются.
- Проверка: `make test` (юнит-тесты 86/86 + promtool) и живой стенд — `make up`, `make pool`,
  `make budget`, переход группы холодно→тепло, смена бюджета, `docker stop` инстанса, короткий
  обрыв etcd.