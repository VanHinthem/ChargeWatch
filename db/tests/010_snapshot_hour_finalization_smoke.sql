\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM schema_migration WHERE version='010_snapshot_hour_finalization')
 THEN RAISE EXCEPTION 'migration 010 not registered'; END IF;
END $$;
INSERT INTO evse_snapshot_active_hour(evse_id,hour_start_utc,category_counts,changed_count,unchanged_count)
SELECT id,'2026-10-24 07:00Z',ARRAY[2,1,0,0,0,0,0,0,0],2,1 FROM evse ORDER BY id LIMIT 1;
INSERT INTO location_snapshot_active_hour(location_id,hour_start_utc,category_counts,changed_count,unchanged_count)
SELECT id,'2026-10-24 07:00Z',ARRAY[1,2,0,0,0],2,1 FROM charging_location ORDER BY id LIMIT 1;
SELECT * FROM snapshot_finalize_hours('2026-10-24 07:00Z',6,9,0.05248399218516142);
DO $$
DECLARE n integer;
BEGIN
 SELECT count(*) INTO n FROM evse_snapshot_active_hour WHERE hour_start_utc='2026-10-24 07:00Z';
 IF n<>0 THEN RAISE EXCEPTION 'EVSE active hour not consumed'; END IF;
 SELECT count(*) INTO n FROM location_snapshot_active_hour WHERE hour_start_utc='2026-10-24 07:00Z';
 IF n<>0 THEN RAISE EXCEPTION 'location active hour not consumed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM evse_snapshot_weekday_profile
 WHERE iso_weekday=6 AND sample_count[10]=3
   AND abs(category_ewma[10]-2.0/3.0)<0.0001
   AND abs(category_ewma[34]-1.0/3.0)<0.0001
   AND changed_feed_count[10]=2 AND unchanged_feed_count[10]=1)
 THEN RAISE EXCEPTION 'EVSE profile first aggregation incorrect'; END IF;
 IF NOT EXISTS(SELECT 1 FROM location_snapshot_weekday_profile
 WHERE iso_weekday=6 AND sample_count[10]=3
   AND abs(category_ewma[10]-1.0/3.0)<0.0001)
 THEN RAISE EXCEPTION 'location profile incorrect'; END IF;
END $$;
-- A second invocation must leave counts unchanged (idempotent finalization).
SELECT * FROM snapshot_finalize_hours('2026-10-24 07:00Z',6,9,0.05248399218516142);
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM evse_snapshot_weekday_profile WHERE sample_count[10]<>3)
 THEN RAISE EXCEPTION 'second finalization double-counted'; END IF;
END $$;
-- Repeat the exact local slot seven days later, with changed distribution.
INSERT INTO evse_snapshot_active_hour(evse_id,hour_start_utc,category_counts,changed_count,unchanged_count)
SELECT id,'2026-10-31 08:00Z',ARRAY[0,3,0,0,0,0,0,0,0],0,3 FROM evse ORDER BY id LIMIT 1;
SELECT * FROM snapshot_finalize_hours('2026-10-31 08:00Z',6,9,0.05248399218516142);
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM evse_snapshot_weekday_profile
  WHERE iso_weekday=6 AND sample_count[10]=6
    AND abs(category_ewma[10]-(2.0/3.0)*(1-0.05248399218516142))<0.0001
    AND changed_feed_count[10]=2 AND unchanged_feed_count[10]=4)
 THEN RAISE EXCEPTION 'weekly EWMA not applied once per hourly observation'; END IF;
END $$;
-- Reject a wrong caller-provided Amsterdam slot even without eligible assets.
DO $guard$
BEGIN
  BEGIN
    PERFORM * FROM snapshot_finalize_hours('2026-10-24 07:00Z',6,8,0.05248399218516142);
    RAISE EXCEPTION 'wrong local slot was accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM <> 'local slot must match UTC hour in Europe/Amsterdam' THEN
      RAISE;
    END IF;
  END;
END $guard$;

-- Autumn fallback: both UTC 00:00 and 01:00 map to Sunday 02:00.
DO $dst$
BEGIN
  PERFORM * FROM snapshot_finalize_hours('2026-10-25 00:00Z',7,2,0.05248399218516142);
  PERFORM * FROM snapshot_finalize_hours('2026-10-25 01:00Z',7,2,0.05248399218516142);
END $dst$;
ROLLBACK;
