# Spec Delta

## MODIFIED Requirements

### Requirement: Экземпляр SHALL NOT принимать запросы обслуживания, пока не получил конфигурацию из хранилища. Получение конфигурации SHALL открывать трафик. Полученная, но отклонённая конфигурация SHALL NOT удерживать трафик: значение из хранилища получено, экземпляр становится готовым, а пул работает на последних рабочих значениях. Снятие конфигурации (исчезновение всех распознанных ключей по реальным событиям хранилища — например, провижер удалил ключи инстанса, потерявшего долю бюджета или ставшего частью холодного флота) SHALL закрывать трафик до тех пор, пока конфигурация не появится снова. Экземпляр, принадлежащий неактивной группе с положительным резервом, конфигурацию SHALL получать и на резервном размере SHALL продолжать обслуживать трафик. Экземпляр, принадлежащий неактивной группе с нулевым резервом, конфигурации SHALL NOT иметь и SHALL NOT обслуживать трафик. Недоступность хранилища сама по себе SHALL NOT закрывать трафик: события в этом случае не приходят, откат происходит только по фактическим изменениям ключей.

When `pool.etcd.enabled` is `false`, traffic SHALL be accepted immediately after static configuration is successfully applied and the pool is initialized. Liveness SHALL remain independent of etcd availability. The traffic gate SHALL allow requests in static mode.

#### Scenario: Traffic allowed immediately in static mode
- **WHEN** `pool.etcd.enabled` is `false` and the pool is initialized with static config
- **THEN** `/api/work` SHALL be accessible (not return 503 due to missing etcd config)
- **AND** the traffic gate SHALL allow requests

#### Scenario: Readiness reflects static configuration readiness
- **WHEN** static configuration is applied successfully
- **THEN** readiness SHALL reflect that the pool is configured and ready
- **AND** liveness SHALL remain independent of etcd/static source
