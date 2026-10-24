-- Bounded durable compression of unchanged Locations responses.
-- One pending UTC hour per source, not one row per poll or asset.
-- Must be flushed against the SAME catalog snapshot before a changed-feed import.
CREATE TABLE IF NOT EXISTS snapshot_unchanged_hour (
  source_name text PRIMARY KEY,
  hour_start_utc timestamptz NOT NULL,
  snapshot_sha256 text NOT NULL CHECK (length(snapshot_sha256)=64),
  poll_count integer NOT NULL CHECK (poll_count>0),
  first_observed_at timestamptz NOT NULL,
  last_observed_at timestamptz NOT NULL,
  CHECK (first_observed_at<=last_observed_at),
  CHECK (first_observed_at>=hour_start_utc AND first_observed_at<hour_start_utc+interval '1 hour'),
  CHECK (last_observed_at>=hour_start_utc AND last_observed_at<hour_start_utc+interval '1 hour')
);
GRANT SELECT,INSERT,UPDATE,DELETE ON snapshot_unchanged_hour TO chargewatch_import;
GRANT SELECT ON snapshot_unchanged_hour TO chargewatch_read;
INSERT INTO schema_migration(version) VALUES ('011_snapshot_unchanged_hour')
ON CONFLICT DO NOTHING;
