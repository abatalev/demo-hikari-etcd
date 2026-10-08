# Spec Delta

## MODIFIED Requirements

### Requirement: Сервис SHALL отдавать в виде метрик состояние источника конфигурации — признак активности подписки, текущую ревизию, число применений и отклонений конфигурации, число непрочитанных значений и признак наличия ошибок, — а также признак готовности принимать трафик. Причину неготовности метрики SHALL NOT раскрывать в значениях: она передаётся меткой с ограниченным набором значений, а её человеческий текст — в признаке готовности процесса, а не в метрике. Метрики SHALL NOT содержать пароль доступа к базе ни при каких обстоятельствах.

When `pool.etcd.enabled` is `false`, etcd-related readiness/health contributions SHALL NOT cause overall readiness or health to be DOWN. Etcd-specific indicators SHALL reflect the disabled/non-applicable state and pool metrics SHALL reflect statically configured values.

#### Scenario: Readiness UP in static mode
- **WHEN** `pool.etcd.enabled` is `false` and pool is initialized statically
- **THEN** readiness probe SHALL indicate UP when pool and database are ready
- **AND** etcd availability SHALL not be a contributing failure factor

#### Scenario: Health indicators account for disabled etcd
- **WHEN** etcd is disabled
- **THEN** etcd-specific health indicators SHALL reflect a disabled/non-applicable state
- **AND** they SHALL NOT cause overall health to be DOWN
- **AND** pool metrics SHALL correctly reflect the statically configured pool size
