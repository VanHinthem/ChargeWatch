-- PR #17: durable, bounded poll identity and current-hour categorical accumulators.
-- Not wired to collector yet. No per-poll or long-lived hourly history.
-- A single monotonic checkpoint is sufficient only for one serialized collector;
-- retries must use the same poll identity, and an older poll must never reapply.
CREATE TABLE IF NOT EXISTS snapshot_poll_checkpoint (
  source_name text PRIMARY KEY,
  last_poll_id text NOT NULL,
  last_observed_at timestamptz NOT NULL,
  last_quality text NOT NULL CHECK (last_quality IN ('changed_feed','unchanged_feed')),
  accepted_poll_count bigint NOT NULL DEFAULT 1 CHECK (accepted_poll_count >= 1)
);

CREATE TABLE IF NOT EXISTS evse_snapshot_active_hour (
  evse_id bigint PRIMARY KEY REFERENCES evse(id) ON DELETE CASCADE,
  hour_start_utc timestamptz NOT NULL,
  category_counts integer[] NOT NULL DEFAULT array_fill(0, ARRAY[9]),
  changed_count integer NOT NULL DEFAULT 0,
  unchanged_count integer NOT NULL DEFAULT 0,
  CHECK (array_length(category_counts,1)=9),
  CHECK (changed_count >= 0 AND unchanged_count >= 0)
);

CREATE TABLE IF NOT EXISTS location_snapshot_active_hour (
  location_id bigint PRIMARY KEY REFERENCES charging_location(id) ON DELETE CASCADE,
  hour_start_utc timestamptz NOT NULL,
  category_counts integer[] NOT NULL DEFAULT array_fill(0, ARRAY[5]),
  changed_count integer NOT NULL DEFAULT 0,
  unchanged_count integer NOT NULL DEFAULT 0,
  CHECK (array_length(category_counts,1)=5),
  CHECK (changed_count >= 0 AND unchanged_count >= 0)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON snapshot_poll_checkpoint,
  evse_snapshot_active_hour, location_snapshot_active_hour TO chargewatch_import;
GRANT SELECT ON snapshot_poll_checkpoint, evse_snapshot_active_hour,
  location_snapshot_active_hour TO chargewatch_read;
INSERT INTO schema_migration(version) VALUES ('009_snapshot_active_hour')
ON CONFLICT DO NOTHING;
