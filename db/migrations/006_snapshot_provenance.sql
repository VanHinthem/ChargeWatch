-- Sprint 3 observation provenance; additive, not yet wired into import runtime.
-- Apply only to isolated chargewatch_statistics_test after review.
CREATE TABLE IF NOT EXISTS ndw_snapshot_checkpoint (
  source_name text PRIMARY KEY,
  source_sha256 char(64) NOT NULL,
  fetched_at timestamptz NOT NULL,
  last_committed_at timestamptz NOT NULL DEFAULT now(),
  location_count integer NOT NULL CHECK (location_count > 0),
  evse_count integer NOT NULL CHECK (evse_count > 0)
);
CREATE TABLE IF NOT EXISTS evse_poll_checkpoint (
  evse_id bigint PRIMARY KEY REFERENCES evse(id) ON DELETE CASCADE,
  observed_at timestamptz NOT NULL,
  status_class text NOT NULL CHECK(status_class IN ('available','occupied','fault','unknown')),
  source_updated_at timestamptz,
  snapshot_sha256 char(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_evse_poll_checkpoint_observed_at ON evse_poll_checkpoint(observed_at);
GRANT SELECT,INSERT,UPDATE,DELETE ON ndw_snapshot_checkpoint,evse_poll_checkpoint TO chargewatch_import;
GRANT SELECT ON ndw_snapshot_checkpoint,evse_poll_checkpoint TO chargewatch_read;
INSERT INTO schema_migration(version) VALUES ('006_snapshot_provenance') ON CONFLICT DO NOTHING;
