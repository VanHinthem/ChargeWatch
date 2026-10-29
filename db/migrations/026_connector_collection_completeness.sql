-- Skip connector-presence evidence only for EVSEs whose connector collection
-- is structurally incomplete in this otherwise accepted Locations snapshot.
-- Requires indexed transaction-local stage_present_connector_ids(id) and
-- stage_connector_presence_excluded_evse_ids(id).
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
    AND NOT EXISTS (
      SELECT 1 FROM stage_connector_presence_excluded_evse_ids excluded
      WHERE excluded.id=c.evse_id)
    AND NOT EXISTS (SELECT 1 FROM stage_present_connector_ids seen WHERE seen.id=c.id)
    AND (c.last_presence_checked_at IS NULL OR c.last_presence_checked_at<p_observed_at);
  GET DIAGNOSTICS n_missing=ROW_COUNT;
  RETURN QUERY SELECT n_present,n_missing;
END $presence$;

GRANT EXECUTE ON FUNCTION reconcile_connector_snapshot_presence(timestamptz) TO chargewatch_import;

INSERT INTO schema_migration(version)
VALUES ('026_connector_collection_completeness')
ON CONFLICT DO NOTHING;
