# Proposal

## Why

Обёртка событий механизма `MechanismObservation` (идентичные копии в `service` и `provisioner`)
разрослась до шести форм вызова и трёх функциональных интерфейсов, из которых половина не
используется: `call(ThrowingFunction)` и `call(ThrowingConsumer)` не вызываются извне вообще —
`call` живёт только как внутренняя реализация `run`. Отказ старта наблюдения моделируется
возвратом `null`, из-за чего `Event` и `call*`-методы несут по полтора десятка проверок
«null или не null», хотя Micrometer сам умеет «ничего не делать» (наблюдение на пустом регистре).
В довесок `run` и `runQuietly` дублируют друг друга: `runQuietly` — это `run` с телом, которому
запрещено бросать проверяемые исключения, и у него ровно один пользователь. Рефакторинг без
изменения поведения — `skip_specs: true`.

## What Changes

- `MechanismObservation` (обе копии): старт наблюдения никогда не возвращает `null` — при отказе
  наблюдения возвращается наблюдение на пустом регистре, которое не пишет ничего; все проверки
  «null или не null» в `callQuietly`, `run` и `Event` исчезают.
- Удаляются `call(ThrowingFunction)`, `call(ThrowingConsumer)` и интерфейсы `ThrowingFunction`,
  `ThrowingConsumer`; `run(ThrowingRunnable)` реализуется напрямую.
- `runQuietly` удаляется: единственный пользователь — `ManagedPool.create` — переводится на
  `start` + `Event` с маркировкой ERROR в inner try/catch; `run` остаётся единственной формой
  «тело события с результатом наружу» и продолжает обслуживать `UnreleasedShrinkPublisher`
  (ему нужен проброс проверяемых исключений).
- `Event.failure` получает живых пользователей: провалы пересчёта флота
  (`ProvisioningWorker.provision`) и создания пула (`ManagedPool.create`) маркируют событие ERROR.
  Маркировка выполняется в inner try/catch **до** `close()`: у try-with-resources ресурс
  закрывается раньше `catch`, и у завершённого спана статус уже не изменить.
- Поверхность форм сводится к `event`, `start` + `Event`, `callQuietly`, `run` и
  `ThrowingRunnable`.
- Поведение не меняется: события отбираются общей долей
  `management.tracing.sampling.probability`, признаки не отменяют событие, отказ наблюдения
  не влияет на пул и провижёр, итог после `start()` дописывается в то же событие.

## Impact

Убираются `call`, `callQuietly`-обёртка `runQuietly` и два интерфейса; `ManagedPool.create`
переходит с `runQuietly` на `start`/`Event` (тот же идиоматический паттерн, что у пересчёта
флота), `ProvisioningWorker.provision` получает маркировку ERROR при провале. Тесты
`MechanismObservationTest` (оба модуля): кейсы удаляемой формы `call` переводятся на
`callQuietly`, кейс `quietVariantDoesNotSwallowPoolError` переписывается в
`openEventFailureMarksErrorAndPropagates`, `noopIsSafe` переводится на `run`, в провизёре
добавляется симметричный кейс открытого события.