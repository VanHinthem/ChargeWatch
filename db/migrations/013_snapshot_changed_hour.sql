-- One verified HTTP 200 observation AFTER catalog publish, in the same
-- import transaction. Record one category sample per visible asset.
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
 FROM evse e
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
 FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id GROUP BY l.id
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
 IF ne<>(SELECT count(*) FROM evse) OR nl<>(SELECT count(*) FROM charging_location) THEN
 RAISE EXCEPTION 'changed-feed sample missed assets'; END IF;
 RETURN QUERY SELECT ne,nl;
END $fn$;
GRANT EXECUTE ON FUNCTION snapshot_record_changed_hour(timestamptz) TO chargewatch_import;
INSERT INTO schema_migration(version) VALUES ('013_snapshot_changed_hour') ON CONFLICT DO NOTHING;
