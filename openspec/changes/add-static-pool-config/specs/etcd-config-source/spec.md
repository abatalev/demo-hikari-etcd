# Spec Delta

## MODIFIED Requirements

### Requirement: Сервис SHALL поддерживать режим, в котором etcd не опрашивается вовсе, а конфигурация берётся только из локальных значений по умолчанию. В этом режиме сервис SHALL сообщать, что источник отключён, чтобы отличить его от сбоя связи.

When `pool.etcd.enabled` is `false`, the system SHALL NOT connect to etcd endpoints, SHALL NOT build etcd paths for watching, SHALL NOT start watch/registration/publication threads, and SHALL report the source as disabled. Path validation for etcd segments SHALL be skipped in this mode.

#### Scenario: Etcd source disabled when enabled=false
- **WHEN** `pool.etcd.enabled` is `false`
- **THEN** the etcd config source SHALL not start watch, registration, or publication threads
- **AND** the source SHALL report as disabled
- **AND** no connection attempts to etcd endpoints SHALL be made

### Requirement: При включённом источнике конфигурации сегменты пути SHALL быть непустыми и SHALL NOT содержать разделителя пути. Нарушение SHALL прекращать запуск с сообщением, называющим проблемный сегмент.

When `pool.etcd.enabled` is `false`, path segment validation SHALL be skipped.

#### Scenario: Path validation only applies when enabled
- **WHEN** `pool.etcd.enabled` is `false`
- **THEN** validation of path segments (group/instance) for the etcd path SHALL NOT be performed
- **AND** the system SHALL use static configuration instead
