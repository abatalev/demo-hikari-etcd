-- Демо-таблица, чтобы /api/work делал настоящий запрос, а не SELECT 1.
CREATE TABLE IF NOT EXISTS demo_items (
    id         bigserial PRIMARY KEY,
    name       text        NOT NULL,
    amount     numeric(12,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO demo_items (name, amount)
SELECT 'item-' || g, round((random() * 1000)::numeric, 2)
FROM generate_series(1, 2000) AS g;

ANALYZE demo_items;

-- Сколько сессий держит пул сервиса: application_name ставится в HikariConfig.
CREATE OR REPLACE VIEW pool_sessions AS
SELECT application_name,
       count(*)                                   AS sessions,
       count(*) FILTER (WHERE state = 'active')  AS active,
       count(*) FILTER (WHERE state = 'idle')    AS idle
FROM pg_stat_activity
WHERE application_name IS NOT NULL
GROUP BY application_name;
