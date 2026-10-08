# Spec Delta

## MODIFIED Requirements

### Requirement: Если значение ключа не является числом, сервис SHALL игнорировать только этот ключ — его значение SHALL NOT применяться — и применить остальные ключи конфигурации как обычно. Для размера пула локального значения по умолчанию SHALL NOT существовать, поэтому битый размер SHALL оставаться неприменённым, а не заменяться дефолтом. Причина SHALL быть видна в наблюдаемом состоянии.

The system SHALL also validate static configuration values from `application.yaml` when `pool.etcd.enabled` is `false`. Invalid static values (non-numeric for numeric properties, out of range) SHALL prevent application startup with a clear error message indicating the problematic property. Valid static values SHALL be normalized and applied using the same rules as etcd-driven configuration.

#### Scenario: Static configuration validated on startup
- **WHEN** etcd is disabled and static values are provided
- **THEN** the system SHALL validate `maximumPoolSize`, `minimumIdle`, and relevant timeouts against allowed ranges
- **AND** if validation fails, the application SHALL fail to start with an informative error

#### Scenario: Non-numeric values in static config rejected
- **WHEN** static configuration contains non-numeric values for size/timeouts
- **THEN** validation SHALL reject them and prevent startup
- **AND** the error SHALL clearly indicate the problematic property
