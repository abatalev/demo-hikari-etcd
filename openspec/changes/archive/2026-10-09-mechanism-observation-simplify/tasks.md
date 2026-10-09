# Tasks

## 1. Упрощение `MechanismObservation` (service)

- [x] 1.1 Старт наблюдения никогда не возвращает `null`: при отказе — наблюдение на пустом
  регистре (`noop`); убрать проверки «null или не null» в `callQuietly`, `run`, `Event`
- [x] 1.2 Удалить `call(ThrowingFunction)`, `call(ThrowingConsumer)` и интерфейсы
  `ThrowingFunction`, `ThrowingConsumer`; `run` реализовать напрямую
- [x] 1.3 Удалить `runQuietly`; `ManagedPool.create` перевести с `runQuietly` на `start` + `Event`
  с маркировкой ERROR в inner try/catch (маркировка до `close()` — после закрытия спана статус
  не изменить)

## 2. Упрощение `MechanismObservation` (provisioner)

- [x] 2.1 То же, что 1.1
- [x] 2.2 То же, что 1.2
- [x] 2.3 Удалить `runQuietly`; в `ProvisioningWorker.provision` обернуть `provisionWithin` в inner
  try/catch с `event.failure(e)` — провал пересчёта маркирует событие ERROR

## 3. Тесты

- [x] 3.1 Перевести кейсы `bodyWritesResultAndNoteAfterStart` (service) и
  `recomputeWritesAttributesAndEnds` (provisioner) с `call` на `callQuietly`
- [x] 3.2 Переписать `quietVariantDoesNotSwallowPoolError` (service) в
  `openEventFailureMarksErrorAndPropagates` по паттерну `start`/`Event` + inner catch; `noopIsSafe`
  перевести с `runQuietly` на `run`; добавить симметричный кейс открытого события в провизёр

## 4. Проверки

- [x] 4.1 `mvn test` service и provisioner — зелёные (юнит-тесты, spotbugs, pmd)
- [x] 4.2 `make test` — зелёный (сверки состава, promtool)
- [x] 4.3 Стенд: пересборка образов, `make up`; события механизма по-прежнему видны в Tempo
  при доле 1.0
- [x] 4.4 Стенд после варианта 2: пересборка образов, `make up`; бюджетный ресайз и нагрузка
  работают, события механизма по-прежнему экспортируются