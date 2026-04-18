-- Placeholder init script for PostgreSQL.
-- Real schema will be added in T04.
-- PostgreSQL docker-entrypoint-initdb.d runs all *.sql files alphabetically on first start only.

-- Basic sanity marker
CREATE TABLE IF NOT EXISTS __db_initialized (
    initialized_at TIMESTAMPTZ DEFAULT NOW()
);
INSERT INTO __db_initialized DEFAULT VALUES;
