-- Flush the single compressed 304 hour against the unchanged catalog snapshot.
-- Transactional and idempotent: the pending source row is deleted only after
-- the EVSE + location increments succeed. The caller must serialize with poll
-- claims, finish any preceding hour, and flush before importing a new 200 feed.
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
  SELECT id,pending.hour_start_utc,
    ARRAY(SELECT CASE WHEN n=(CASE upper(coalesce(e.status,''))
         WHEN 'AVAILABLE' THEN 1 WHEN 'CHARGING' THEN 2
         WHEN 'BLOCKED' THEN 3 WHEN 'RESERVED' THEN 4
         WHEN 'INOPERATIVE' THEN 5 WHEN 'OUTOFORDER' THEN 6
         WHEN 'PLANNED' THEN 8 WHEN 'REMOVED' THEN 9 ELSE 7 END)
         THEN pending.poll_count ELSE 0 END
         FROM generate_series(1,9) n ORDER BY n),
    0,pending.poll_count
  FROM evse e
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
    FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id
    GROUP BY l.id
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
  IF evse_rows<>(SELECT count(*) FROM evse)
     OR location_rows<>(SELECT count(*) FROM charging_location) THEN
    RAISE EXCEPTION 'incomplete 304 flush: expected one write per catalog asset';
  END IF;

  DELETE FROM snapshot_unchanged_hour
  WHERE source_name='ndw-locations'
    AND hour_start_utc=pending.hour_start_utc
    AND snapshot_sha256=pending.snapshot_sha256;
  RETURN QUERY SELECT evse_rows,location_rows,pending.poll_count;
END $fn$;

GRANT EXECUTE ON FUNCTION snapshot_flush_unchanged_hour(text) TO chargewatch_import;
INSERT INTO schema_migration(version) VALUES ('012_snapshot_unchanged_flush')
ON CONFLICT DO NOTHING;
