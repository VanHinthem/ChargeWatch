-- Run after 004 on the isolated statistics test database.
-- No committed fixture writes; checks actual permissions and initial state.
BEGIN;
DO $$
DECLARE n integer;
BEGIN
  IF (SELECT count(*) FROM schema_migration WHERE version='004_hour_accumulator')<>1 THEN
    RAISE EXCEPTION 'Migration 004 not registered';
  END IF;
  IF NOT has_table_privilege('chargewatch_import','evse_hour_accumulator','INSERT')
     OR NOT has_table_privilege('chargewatch_import','evse_hour_accumulator','UPDATE')
     OR NOT has_table_privilege('chargewatch_read','evse_hour_accumulator','SELECT') THEN
    RAISE EXCEPTION 'Hour accumulator role grants incomplete';
  END IF;
  SELECT count(*) INTO n FROM evse_hour_accumulator
  WHERE covered_seconds < available_seconds OR covered_seconds > 3600;
  IF n<>0 THEN RAISE EXCEPTION 'Invalid EVSE hour counters'; END IF;
END $$;
ROLLBACK;
