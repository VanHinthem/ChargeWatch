BEGIN;
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM schema_migration WHERE version='007_poll_hour_coverage')
 THEN RAISE EXCEPTION 'Missing migration 007'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.tables
 WHERE table_name='evse_poll_hour_coverage' AND table_schema='public')
 THEN RAISE EXCEPTION 'Missing EVSE poll coverage table'; END IF;
END $$;
ROLLBACK;
