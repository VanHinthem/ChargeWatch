-- Retirement must not affect compact snapshot statistics or their poll checkpoint.
DO $check$
BEGIN
  IF to_regclass('public.evse_poll_hour_coverage') IS NOT NULL
     OR to_regclass('public.evse_poll_checkpoint') IS NOT NULL
  THEN RAISE EXCEPTION 'Legacy poll coverage tables still exist'; END IF;
  IF to_regclass('public.evse_snapshot_weekday_profile') IS NULL
     OR to_regclass('public.location_snapshot_weekday_profile') IS NULL
     OR to_regclass('public.snapshot_poll_checkpoint') IS NULL
     OR to_regclass('public.evse_snapshot_active_hour') IS NULL
     OR to_regclass('public.location_snapshot_active_hour') IS NULL
  THEN RAISE EXCEPTION 'A compact statistics table/checkpoint is missing'; END IF;
  IF NOT EXISTS (SELECT 1 FROM schema_migration
                 WHERE version='023_retire_legacy_poll_coverage')
  THEN RAISE EXCEPTION 'Retirement migration not registered'; END IF;
END $check$;
