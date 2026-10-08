# Spec Delta

## MODIFIED Requirements

### Requirement: Сервис SHALL создавать пул соединений при применении первой принятой конфигурации из хранилища. Локальное значение размера пула по умолчанию SHALL быть нулевым: пока конфигурация не получена, пула SHALL NOT существовать, и приложение SHALL NOT получать соединений. Отсутствие доступности базы данных при создании пула SHALL NOT приводить к отказу сервиса: пул поднимается и добирает соединения позже.

When `pool.etcd.enabled` is `false`, the system SHALL initialize the Hikari pool from static configuration (`pool.db.maximum-pool-size` and `pool.db.minimum-idle`) immediately at startup without waiting for etcd events. The pool SHALL be ready to serve traffic after successful initialization. Invalid static configuration SHALL be rejected with a clear error.

#### Scenario: Pool initialized from static configuration when etcd disabled
- **WHEN** `pool.etcd.enabled` is `false`
- **THEN** the system SHALL initialize the Hikari pool using `pool.db.maximum-pool-size` and `pool.db.minimum-idle` from configuration without waiting for etcd events
- **AND** the pool SHALL be ready to serve traffic after initialization completes

#### Scenario: Pool applies static configuration on startup in static mode
- **WHEN** static configuration is applied at startup
- **THEN** resizing logic SHALL behave consistently with etcd-driven mode for the applied values
- **AND** invalid static configuration SHALL be rejected according to validation rules

### Requirement: Сервис SHALL применять изменения размера пула, минимального числа свободных соединений и таймаутов к работающему пулу без его пересоздания. Запросы, выполняющиеся в момент изменения, SHALL NOT прерываться.

When `pool.etcd.enabled` is `false`, the system SHALL NOT watch etcd for configuration changes, SHALL NOT start etcd worker/registration/publication threads, and the pool size SHALL remain fixed to statically configured values.

#### Scenario: Static mode does not depend on etcd for pool sizing
- **WHEN** `pool.etcd.enabled` is `false`
- **THEN** the system SHALL not watch etcd for pool size changes
- **AND** pool size SHALL remain fixed to static configuration values
- **AND** no etcd-driven resize events SHALL be processed
