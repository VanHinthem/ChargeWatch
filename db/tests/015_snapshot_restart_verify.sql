\set ON_ERROR_STOP on
BEGIN;
DO $check$
BEGIN
 IF (SELECT poll_count FROM snapshot_unchanged_hour WHERE source_name='ndw-locations')<>2
 THEN RAISE EXCEPTION 'pending observations did not survive prior database session'; END IF;
END $check$;
SELECT * FROM snapshot_flush_unchanged_hour(repeat('c',64));
UPDATE evse SET status='CHARGING';
UPDATE ndw_snapshot_checkpoint SET source_sha256=repeat('d',64) WHERE source_name='ndw-locations';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:15Z');
COMMIT;
-- Verify in the second connection after an actual committed transaction.
DO $check$
BEGIN
 IF EXISTS(SELECT 1 FROM snapshot_unchanged_hour) THEN
   RAISE EXCEPTION 'committed flush left buffered 304 observations'; END IF;
 IF NOT EXISTS(SELECT 1 FROM evse_snapshot_active_hour
  WHERE category_counts[1]=2 AND category_counts[2]=1
   AND unchanged_count=2 AND changed_count=1)
 THEN RAISE EXCEPTION 'committed 304-200 transition lost counts'; END IF;
END $check$;
-- CI-only isolated database cleanup; no production history preserved.
DELETE FROM evse_snapshot_active_hour;
DELETE FROM location_snapshot_active_hour;
DELETE FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations';
