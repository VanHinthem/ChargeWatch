-- Issue #73: transactional EVSE presence, preserve history while excluding missing EVSEs.
-- Existing rows start active; missing counts are only advanced on verified complete HTTP 200.
ALTER TABLE evse ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true;
ALTER TABLE evse ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;
ALTER TABLE evse ADD COLUMN IF NOT EXISTS first_missing_at timestamptz;
ALTER TABLE evse ADD COLUMN IF NOT EXISTS last_presence_checked_at timestamptz;
ALTER TABLE evse ADD COLUMN IF NOT EXISTS consecutive_missing_snapshots integer NOT NULL DEFAULT 0
    CHECK (consecutive_missing_snapshots >= 0);
CREATE INDEX IF NOT EXISTS idx_evse_active_location ON evse(location_id)
  WHERE is_active;
-- Executed only by verified HTTP 200 importer after filling the session-local
-- stage_present_evse_ids table (unique index on id). Kept as a SQL function to
-- exercise disappearance, reappearance and replay with isolated DB fixtures.
CREATE OR REPLACE FUNCTION reconcile_evse_snapshot_presence(p_observed_at timestamptz)
RETURNS TABLE(present_rows bigint, missing_rows_processed bigint)
LANGUAGE plpgsql AS $presence$
DECLARE n_present bigint;
DECLARE n_missing bigint;
BEGIN
  UPDATE evse e SET
    is_active=true,
    last_seen_at=p_observed_at,
    first_missing_at=NULL,
    last_presence_checked_at=NULL,
    consecutive_missing_snapshots=0
  FROM stage_present_evse_ids seen
  WHERE seen.id=e.id
    AND (NOT e.is_active
         OR e.last_seen_at IS NULL
         OR e.last_seen_at<p_observed_at-interval '24 hours'
         OR e.first_missing_at IS NOT NULL
         OR e.consecutive_missing_snapshots<>0);
  GET DIAGNOSTICS n_present=ROW_COUNT;

  UPDATE evse e SET
    first_missing_at=COALESCE(e.first_missing_at,p_observed_at),
    consecutive_missing_snapshots=e.consecutive_missing_snapshots+1,
    last_presence_checked_at=p_observed_at,
    is_active=NOT (
      e.consecutive_missing_snapshots+1>=3
      AND p_observed_at-COALESCE(e.first_missing_at,p_observed_at)>=interval '10 minutes')
  FROM charging_location l
  WHERE e.location_id=l.id AND l.is_active AND e.is_active
    AND NOT EXISTS (SELECT 1 FROM stage_present_evse_ids seen WHERE seen.id=e.id)
    AND (e.last_presence_checked_at IS NULL OR e.last_presence_checked_at<p_observed_at);
  GET DIAGNOSTICS n_missing=ROW_COUNT;
  RETURN QUERY SELECT n_present,n_missing;
END $presence$;
GRANT EXECUTE ON FUNCTION reconcile_evse_snapshot_presence(timestamptz) TO chargewatch_import;

INSERT INTO schema_migration(version) VALUES ('021_evse_snapshot_presence') ON CONFLICT DO NOTHING;

-- Reinstall live function definitions from migration 014 with active-only filters.
-- Existing historical profiles remain unchanged.
CREATE OR REPLACE FUNCTION snapshot_flush_unchanged_hour(p_expected_sha text)
RETURNS TABLE(evses_written bigint, locations_written bigint, polls_applied integer)
LANGUAGE plpgsql AS $fn$
DECLARE
  pending snapshot_unchanged_hour%ROWTYPE;
  actual_sha text;
  evse_rows bigint;
  location_rows bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(24680,17017);
  SELECT * INTO pending FROM snapshot_unchanged_hour
    WHERE source_name='ndw-locations' FOR UPDATE;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 0::bigint,0::bigint,0::integer;
    RETURN;
  END IF;
  SELECT source_sha256 INTO actual_sha FROM ndw_snapshot_checkpoint
    WHERE source_name='ndw-locations';
  IF actual_sha IS DISTINCT FROM pending.snapshot_sha256
     OR p_expected_sha IS DISTINCT FROM pending.snapshot_sha256 THEN
    RAISE EXCEPTION 'unchanged-hour snapshot does not match committed catalog';
  END IF;
  IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour
            WHERE hour_start_utc<>pending.hour_start_utc)
     OR EXISTS(SELECT 1 FROM location_snapshot_active_hour
            WHERE hour_start_utc<>pending.hour_start_utc) THEN
    RAISE EXCEPTION 'finalize previous active hour before flushing unchanged observations';
  END IF;

  INSERT INTO evse_snapshot_active_hour
   (evse_id,hour_start_utc,category_counts,changed_count,unchanged_count)
  SELECT e.id,pending.hour_start_utc,
    ARRAY(SELECT CASE WHEN n=(CASE upper(coalesce(e.status,''))
         WHEN 'AVAILABLE' THEN 1 WHEN 'CHARGING' THEN 2
         WHEN 'BLOCKED' THEN 3 WHEN 'RESERVED' THEN 4
         WHEN 'INOPERATIVE' THEN 5 WHEN 'OUTOFORDER' THEN 6
         WHEN 'PLANNED' THEN 8 WHEN 'REMOVED' THEN 9 ELSE 7 END)
         THEN pending.poll_count ELSE 0 END
         FROM generate_series(1,9) n ORDER BY n),
    0,pending.poll_count
  FROM evse e JOIN charging_location l ON l.id=e.location_id AND l.is_active WHERE e.is_active
  ON CONFLICT(evse_id) DO UPDATE SET
    category_counts=ARRAY(
      SELECT evse_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
      FROM generate_series(1,9) n ORDER BY n),
    unchanged_count=evse_snapshot_active_hour.unchanged_count+excluded.unchanged_count
  WHERE evse_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
  GET DIAGNOSTICS evse_rows=ROW_COUNT;

  WITH categories AS (
    SELECT l.id,
      count(e.id) FILTER(WHERE upper(e.status)='AVAILABLE') AS available,
      count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(coalesce(e.status,'')) NOT IN ('PLANNED','REMOVED')) AS active,
      count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(coalesce(e.status,'')) NOT IN
        ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')) AS unknown,
      count(e.id) FILTER(WHERE upper(e.status) IN ('INOPERATIVE','OUTOFORDER')) AS defects
    FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id AND e.is_active
    WHERE l.is_active GROUP BY l.id
  )
  INSERT INTO location_snapshot_active_hour
   (location_id,hour_start_utc,category_counts,changed_count,unchanged_count,free_evse_sum,active_evse_sum,valid_count_samples,excluded_count_samples)
  SELECT id,pending.hour_start_utc,
    ARRAY(SELECT CASE WHEN n=(
       CASE WHEN available>0 THEN 1 WHEN active=0 THEN 5
         WHEN unknown>0 THEN 3 WHEN defects=active THEN 4 ELSE 2 END)
       THEN pending.poll_count ELSE 0 END
       FROM generate_series(1,5) n ORDER BY n),
    0,pending.poll_count,CASE WHEN unknown=0 AND active>0 THEN available*pending.poll_count ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN active*pending.poll_count ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN pending.poll_count ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN 0 ELSE pending.poll_count END
  FROM categories
  ON CONFLICT(location_id) DO UPDATE SET
    category_counts=ARRAY(
      SELECT location_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
      FROM generate_series(1,5) n ORDER BY n),
    unchanged_count=location_snapshot_active_hour.unchanged_count+excluded.unchanged_count,
 free_evse_sum=location_snapshot_active_hour.free_evse_sum+excluded.free_evse_sum,
 active_evse_sum=location_snapshot_active_hour.active_evse_sum+excluded.active_evse_sum,
 valid_count_samples=location_snapshot_active_hour.valid_count_samples+excluded.valid_count_samples,
 excluded_count_samples=location_snapshot_active_hour.excluded_count_samples+excluded.excluded_count_samples
  WHERE location_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
  GET DIAGNOSTICS location_rows=ROW_COUNT;

  -- Guard against silently skipped mismatched-hour conflict rows.
  IF evse_rows<>(SELECT count(*) FROM evse e JOIN charging_location l ON l.id=e.location_id WHERE e.is_active AND l.is_active)
     OR location_rows<>(SELECT count(*) FROM charging_location WHERE is_active) THEN
    RAISE EXCEPTION 'incomplete 304 flush: expected one write per catalog asset';
  END IF;

  DELETE FROM snapshot_unchanged_hour
  WHERE source_name='ndw-locations'
    AND hour_start_utc=pending.hour_start_utc
    AND snapshot_sha256=pending.snapshot_sha256;
  RETURN QUERY SELECT evse_rows,location_rows,pending.poll_count;
END $fn$;


CREATE OR REPLACE FUNCTION snapshot_record_changed_hour(p_observed_at timestamptz)
RETURNS TABLE(evses_written bigint, locations_written bigint)
LANGUAGE plpgsql AS $fn$
DECLARE h timestamptz := date_trunc('hour',p_observed_at);
DECLARE ne bigint;
DECLARE nl bigint;
BEGIN
 PERFORM pg_advisory_xact_lock(24680,17017);
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour WHERE hour_start_utc<>h)
 OR EXISTS(SELECT 1 FROM location_snapshot_active_hour WHERE hour_start_utc<>h) THEN
   RAISE EXCEPTION 'previous compact hour must be finalized before changed-feed sample';
 END IF;
 INSERT INTO evse_snapshot_active_hour
 (evse_id,hour_start_utc,category_counts,changed_count,unchanged_count)
 SELECT e.id,h,ARRAY(
  SELECT CASE WHEN n=CASE upper(btrim(coalesce(e.status,'')))
    WHEN 'AVAILABLE' THEN 1 WHEN 'CHARGING' THEN 2 WHEN 'BLOCKED' THEN 3
    WHEN 'RESERVED' THEN 4 WHEN 'INOPERATIVE' THEN 5 WHEN 'OUTOFORDER' THEN 6
    WHEN 'PLANNED' THEN 8 WHEN 'REMOVED' THEN 9 ELSE 7 END
   THEN 1 ELSE 0 END FROM generate_series(1,9) n ORDER BY n),1,0
 FROM evse e JOIN charging_location l ON l.id=e.location_id AND l.is_active WHERE e.is_active
 ON CONFLICT(evse_id) DO UPDATE SET
 category_counts=ARRAY(SELECT evse_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
 FROM generate_series(1,9) n ORDER BY n),
 changed_count=evse_snapshot_active_hour.changed_count+1
 WHERE evse_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
 GET DIAGNOSTICS ne=ROW_COUNT;
 WITH categories AS (
 SELECT l.id,
 count(e.id) FILTER(WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE') available,
 count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')) active,
 count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(btrim(coalesce(e.status,''))) NOT IN
 ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')) unknown,
 count(e.id) FILTER(WHERE upper(btrim(coalesce(e.status,''))) IN ('INOPERATIVE','OUTOFORDER')) defects
 FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id AND e.is_active WHERE l.is_active GROUP BY l.id
 )
 INSERT INTO location_snapshot_active_hour
 (location_id,hour_start_utc,category_counts,changed_count,unchanged_count,free_evse_sum,active_evse_sum,valid_count_samples,excluded_count_samples)
 SELECT id,h,ARRAY(SELECT CASE WHEN n=CASE WHEN available>0 THEN 1 WHEN active=0 THEN 5
 WHEN unknown>0 THEN 3 WHEN defects=active THEN 4 ELSE 2 END THEN 1 ELSE 0 END
 FROM generate_series(1,5) n ORDER BY n),1,0,CASE WHEN unknown=0 AND active>0 THEN available ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN active ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN 1 ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN 0 ELSE 1 END FROM categories
 ON CONFLICT(location_id) DO UPDATE SET
 category_counts=ARRAY(SELECT location_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
 FROM generate_series(1,5) n ORDER BY n),
 changed_count=location_snapshot_active_hour.changed_count+1,
 free_evse_sum=location_snapshot_active_hour.free_evse_sum+excluded.free_evse_sum,
 active_evse_sum=location_snapshot_active_hour.active_evse_sum+excluded.active_evse_sum,
 valid_count_samples=location_snapshot_active_hour.valid_count_samples+excluded.valid_count_samples,
 excluded_count_samples=location_snapshot_active_hour.excluded_count_samples+excluded.excluded_count_samples
 WHERE location_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
 GET DIAGNOSTICS nl=ROW_COUNT;
 IF ne<>(SELECT count(*) FROM evse e JOIN charging_location l ON l.id=e.location_id WHERE e.is_active AND l.is_active) OR nl<>(SELECT count(*) FROM charging_location WHERE is_active) THEN
 RAISE EXCEPTION 'changed-feed sample missed assets'; END IF;
 RETURN QUERY SELECT ne,nl;
END $fn$;
