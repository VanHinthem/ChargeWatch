-- Sprint 3 incremental schema: deliberately separate from 003 because 003 has
-- already been applied on a local test database.
CREATE TABLE IF NOT EXISTS evse_hour_accumulator (
    evse_id bigint NOT NULL REFERENCES evse(id) ON DELETE CASCADE,
    hour_start_utc timestamptz NOT NULL,
    last_interval_end timestamptz NOT NULL,
    covered_seconds integer NOT NULL DEFAULT 0 CHECK (covered_seconds BETWEEN 0 AND 3600),
    available_seconds integer NOT NULL DEFAULT 0 CHECK (available_seconds BETWEEN 0 AND 3600),
    finalized_at timestamptz,
    PRIMARY KEY (evse_id, hour_start_utc),
    CHECK (available_seconds <= covered_seconds),
    CHECK (last_interval_end >= hour_start_utc
       AND last_interval_end <= hour_start_utc + interval '1 hour')
);
CREATE INDEX IF NOT EXISTS ix_evse_hour_accumulator_unfinalized
  ON evse_hour_accumulator(hour_start_utc) WHERE finalized_at IS NULL;
GRANT SELECT, INSERT, UPDATE, DELETE ON evse_hour_accumulator TO chargewatch_import;
GRANT SELECT ON evse_hour_accumulator TO chargewatch_read;
INSERT INTO schema_migration(version) VALUES ('004_hour_accumulator') ON CONFLICT DO NOTHING;
