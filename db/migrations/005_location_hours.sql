-- Location hour coverage, independent of EVSE probabilities.
CREATE TABLE IF NOT EXISTS location_hour_accumulator (
  location_id bigint NOT NULL REFERENCES charging_location(id) ON DELETE CASCADE,
  hour_start_utc timestamptz NOT NULL,
  first_interval_start timestamptz NOT NULL,
  last_interval_end timestamptz NOT NULL,
  covered_seconds integer NOT NULL CHECK (covered_seconds BETWEEN 0 AND 3600),
  available_seconds integer NOT NULL CHECK (available_seconds BETWEEN 0 AND 3600),
  finalized_at timestamptz,
  PRIMARY KEY(location_id,hour_start_utc),
  CHECK (available_seconds <= covered_seconds),
  CHECK (first_interval_start >= hour_start_utc AND first_interval_start <= last_interval_end),
  CHECK (last_interval_end <= hour_start_utc + interval '1 hour')
);
CREATE INDEX IF NOT EXISTS ix_location_hour_unfinalized ON location_hour_accumulator(hour_start_utc) WHERE finalized_at IS NULL;
GRANT SELECT,INSERT,UPDATE,DELETE ON location_hour_accumulator TO chargewatch_import;
GRANT SELECT ON location_hour_accumulator TO chargewatch_read;
INSERT INTO schema_migration(version) VALUES ('005_location_hours') ON CONFLICT DO NOTHING;
