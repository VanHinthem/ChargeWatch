\set ON_ERROR_STOP on
BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM schema_migration WHERE version='008_compact_snapshot_profiles') THEN
    RAISE EXCEPTION 'migration 008_compact_snapshot_profiles not applied';
  END IF;

  IF NOT has_table_privilege('chargewatch_import','evse_snapshot_weekday_profile','SELECT,INSERT,UPDATE,DELETE') THEN
    RAISE EXCEPTION 'chargewatch_import EVSE profile privileges missing';
  END IF;

  IF NOT has_table_privilege('chargewatch_read','evse_snapshot_weekday_profile','SELECT') THEN
    RAISE EXCEPTION 'chargewatch_read EVSE profile SELECT missing';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema='public'
      AND table_name IN ('evse_snapshot_weekday_profile','location_snapshot_weekday_profile')
      AND column_name ILIKE '%observed%'
  ) THEN
    RAISE EXCEPTION 'compact profile schema must not contain per-observation/history columns';
  END IF;
END $$;

-- Use one existing entity when available; leave no data after rollback.
WITH target AS (SELECT id FROM evse ORDER BY id LIMIT 1)
INSERT INTO evse_snapshot_weekday_profile(evse_id,iso_weekday)
SELECT id,1 FROM target
ON CONFLICT DO NOTHING;

WITH target AS (SELECT id FROM charging_location ORDER BY id LIMIT 1)
INSERT INTO location_snapshot_weekday_profile(location_id,iso_weekday)
SELECT id,1 FROM target
ON CONFLICT DO NOTHING;

DO $$
DECLARE
  evse_bad integer;
  loc_bad integer;
BEGIN
  SELECT count(*) INTO evse_bad
  FROM evse_snapshot_weekday_profile
  WHERE array_length(category_ewma,1)<>216
     OR array_length(sample_count,1)<>24
     OR array_length(changed_feed_count,1)<>24
     OR array_length(unchanged_feed_count,1)<>24;

  SELECT count(*) INTO loc_bad
  FROM location_snapshot_weekday_profile
  WHERE array_length(category_ewma,1)<>120
     OR array_length(sample_count,1)<>24
     OR array_length(changed_feed_count,1)<>24
     OR array_length(unchanged_feed_count,1)<>24;

  IF evse_bad<>0 OR loc_bad<>0 THEN
    RAISE EXCEPTION 'compact profile array shape invalid';
  END IF;
END $$;

ROLLBACK;
