-- Issue #73: connector presence per verified complete NDW locations snapshot.
-- Retain connector identities and historical connector_tariff references.
ALTER TABLE connector ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS first_missing_at timestamptz;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS last_presence_checked_at timestamptz;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS consecutive_missing_snapshots integer NOT NULL DEFAULT 0
    CHECK (consecutive_missing_snapshots >= 0);
CREATE INDEX IF NOT EXISTS idx_connector_active_evse ON connector(evse_id) WHERE is_active;

-- Requires an indexed transaction-local stage_present_connector_ids(id).
-- Only live EVSEs at live locations accrue missing evidence. An inactive parent
-- already hides all its connectors; reappearance is tracked independently.
CREATE OR REPLACE FUNCTION reconcile_connector_snapshot_presence(p_observed_at timestamptz)
RETURNS TABLE(present_rows bigint, missing_rows_processed bigint)
LANGUAGE plpgsql AS $presence$
DECLARE n_present bigint;
DECLARE n_missing bigint;
BEGIN
  UPDATE connector c SET
    is_active=true,
    last_seen_at=p_observed_at,
    first_missing_at=NULL,
    last_presence_checked_at=NULL,
    consecutive_missing_snapshots=0
  FROM stage_present_connector_ids seen
  WHERE seen.id=c.id
    AND (NOT c.is_active
         OR c.last_seen_at IS NULL
         OR c.last_seen_at<p_observed_at-interval '24 hours'
         OR c.first_missing_at IS NOT NULL
         OR c.consecutive_missing_snapshots<>0);
  GET DIAGNOSTICS n_present=ROW_COUNT;

  UPDATE connector c SET
    first_missing_at=COALESCE(c.first_missing_at,p_observed_at),
    consecutive_missing_snapshots=c.consecutive_missing_snapshots+1,
    last_presence_checked_at=p_observed_at,
    is_active=NOT (
      c.consecutive_missing_snapshots+1>=3
      AND p_observed_at-COALESCE(c.first_missing_at,p_observed_at)>=interval '10 minutes')
  FROM evse e JOIN charging_location l ON l.id=e.location_id
  WHERE c.evse_id=e.id AND e.is_active AND l.is_active AND c.is_active
    AND NOT EXISTS (SELECT 1 FROM stage_present_connector_ids seen WHERE seen.id=c.id)
    AND (c.last_presence_checked_at IS NULL OR c.last_presence_checked_at<p_observed_at);
  GET DIAGNOSTICS n_missing=ROW_COUNT;
  RETURN QUERY SELECT n_present,n_missing;
END $presence$;
GRANT EXECUTE ON FUNCTION reconcile_connector_snapshot_presence(timestamptz) TO chargewatch_import;

INSERT INTO schema_migration(version) VALUES ('022_connector_snapshot_presence') ON CONFLICT DO NOTHING;
