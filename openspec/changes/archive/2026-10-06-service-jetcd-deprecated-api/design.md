# Design

## Context

См. proposal.md — мотивация. Ограничения, формирующие подход:

- jetcd зафиксирован на `0.8.7` (`service/pom.xml`, `<jetcd.version>`), обновление версии —
  вне объёма.
- Инварианты AGENTS.md, которые нельзя задеть: watch стартует с `revision + 1` снимка
  (`withRevision(fromRevision)`), обрыв etcd не влияет на пул, публикация не блокирует watch,
  регистрация не трогает конфиг-воркер. Ни один из инвариантов не зависит от способа задания
  префикса в `WatchOption`.
- Watch у сервиса один (`etcd-config-watch`), создаётся фасадом; правка касается только вызовов
  опций внутри коллабораторов.

## Goals / Non-Goals

**Goals:**
- Убрать предупреждения deprecated в обоих модулях: 4 вызова в 3 файлах сервиса и 7 вызовов в 2
  файлах провизора.
- Оставить проволочные запросы к etcd байт-в-байт теми же (см. Decisions).

**Non-Goals:**
- Обновление jetcd до новее 0.8.7 не рассматривается (оба модуля припинены к `0.8.7`).
- `LoadGen` не трогается (`HttpClient.newBuilder()` — JDK, не jetcd).

## Decisions

**Замена `withPrefix(ByteSequence)` на `isPrefix(boolean)` эквивалентна на проволоке.**

По коду `WatchImpl.resume()` вjetcd 0.8.7 (из sources-jar):
- `withPrefix(prefix)` в билдере опции вычисляет `endKey = OptionsUtil.prefixEndOf(prefix)` и кладёт
  его в `rangeEnd` запроса;
- `isPrefix(true)` с тем же watch-key (а watch-key у нас и есть префикс пути)
  оставляет `endKey` пустым, и соединение само считает
  `rangeEnd = OptionsUtil.prefixEndOf(key)` — тот же байт-конец.

Итоговый `WatchCreateRequest` идентичен: `key=prefix`, `rangeEnd=prefixEndOf(prefix)`,
`startRevision=fromRevision`, `prevKv=true`. Альтернатива (сохранить `withRange` вручную:
`WatchOption.builder().withRange(OptionsUtil.prefixEndOf(prefix))`) даёт тот же запрос, но тянет
приватный `OptionsUtil` и не является рекомендованным API — отклонена.

**Одновременно `newBuilder()` → `builder()`.** В 0.8.7 `newBuilder()` помечен `@Deprecated` во
всех Option-классах и делегирует в `builder()` (проверено в сорсах `WatchOption`, `GetOption`,
`PutOption`, `DeleteOption`). Замена косметическая, идентичного поведения. В провизоре это
затрагивает `GetOption` ×3, `DeleteOption` и `PutOption`.

**Эквивалентность в провизоре подтверждена по `Requests` (не только по `WatchImpl`).**
- `GetOption`/`DeleteOption` маршрутизируются через `Requests.mapRangeRequest`/`mapDeleteRequest`:
  общий `defineRangeRequestEnd` при пустом `endKey` и `isPrefix()` сам считает
  `rangeEnd = OptionsUtil.prefixEndOf(key)` — тот же байт-конец, что даёт `withPrefix`.
- Случай выборов (`ConfigProvisioner.electFor`): `get(bs(prefix), withPrefix(bs(prefix)) +
  withSortField(CREATE) + withSortOrder(ASCEND) + withLimit(1))`. Ключ `get` и `withPrefix` — один
  и тот же `bs(prefix)`, поэтому `isPrefix(true)` даёт тот же `rangeEnd`; сортировка и лимит от
  префикса не зависят — `RangeRequest` идентичен.
- Watch провизора (`ProvisioningWorker.watch`): тот же аргумент, что у сервиса — watch-key и
  префикс поддерева одно и то же, `WatchImpl.resume()` даёт `rangeEnd = prefixEndOf(key)`.

**Снимок (`GetOption`) тоже переводится.** В `EtcdConfigWorker.snapshot()` и всех снимках
провизора уже используется `GetOption.newBuilder().isPrefix(true)` — меняется только фабрика
билдера на `builder()`, опции остаются.

## Risks / Trade-offs

- [Отличие поведения `withPrefix`/`isPrefix` из-за разницы в реализации] → Исключено кодом:
  оба пути дают один `rangeEnd` (см. Decisions, включая проверку `Requests.defineRangeRequestEnd`
  для Get/Delete и выборов); сверка на стенде дополнительно проверяет, что конфигурация
  применяется, гейт открыт и лидер выборов не менялся.
- [Правка двух строк в `EtcdConfigWorker.watch()`/`ProvisioningWorker.watch()` сломает порядок
  инварианта `revision + 1`] → `withRevision(fromRevision)` переносится без изменений; порядок
  вызовов билдера не меняет запрос.
- [Регрессия не ловится юнит-тестами (watch-циклы тестами не покрыты)] → Проверка на живом
  стенде: `make up`, `make pool`, `make budget`, `make leader`, переход группы и наблюдение за
  `pool_generation` (пул не должен пересоздаваться).