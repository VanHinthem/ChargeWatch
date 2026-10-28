-- No legacy duration objects; compact snapshot statistics remain intact.
DO $check$
DECLARE name text;
BEGIN
  FOREACH name IN ARRAY ARRAY[
    'evse_daily_reliability','evse_duration_summary',
    'location_zero_available_summary','evse_occupancy_profile',
    'location_occupancy_profile','evse_hour_accumulator',
    'location_hour_accumulator','evse_observation_checkpoint',
    'location_observation_checkpoint'
  ] LOOP
    IF to_regclass('public.' || name) IS NOT NULL THEN
      RAISE EXCEPTION 'Legacy statistics table % still exists',name;
    END IF;
  END LOOP;
  FOREACH name IN ARRAY ARRAY[
    'evse_snapshot_weekday_profile','location_snapshot_weekday_profile',
    'evse_snapshot_active_hour','location_snapshot_active_hour',
    'snapshot_poll_checkpoint','ndw_snapshot_checkpoint'
  ] LOOP
    IF to_regclass('public.' || name) IS NULL THEN
      RAISE EXCEPTION 'Required compact statistics/checkpoint table % missing',name;
    END IF;
  END LOOP;
  IF NOT EXISTS(SELECT 1 FROM schema_migration
                WHERE version='024_retire_legacy_duration_statistics')
  THEN RAISE EXCEPTION 'Migration 024 not registered'; END IF;
END $check$;
