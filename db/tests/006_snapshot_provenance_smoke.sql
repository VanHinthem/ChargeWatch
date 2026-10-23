-- Run only against isolated chargewatch_statistics_test after migration 006.
BEGIN;
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM schema_migration WHERE version='006_snapshot_provenance')
 THEN RAISE EXCEPTION 'Missing migration 006_snapshot_provenance'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns
   WHERE table_schema='public' AND table_name='ndw_snapshot_checkpoint' AND column_name='fetched_at')
 THEN RAISE EXCEPTION 'Missing provenance timestamp'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns
   WHERE table_schema='public' AND table_name='evse_poll_checkpoint' AND column_name='snapshot_sha256')
 THEN RAISE EXCEPTION 'Missing EVSE poll checkpoint'; END IF;
END $$;
INSERT INTO ndw_snapshot_checkpoint (source_name,source_sha256,fetched_at,location_count,evse_count)
VALUES ('sprint3-provenance-smoke',repeat('a',64),'2026-10-20T10:00:00Z',1,2);
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM ndw_snapshot_checkpoint WHERE source_name='sprint3-provenance-smoke'
 AND location_count=1 AND evse_count=2) THEN
   RAISE EXCEPTION 'Provenance insert failed';
 END IF;
END $$;
ROLLBACK;
