\set ON_ERROR_STOP on
-- End-to-end database transaction semantics: 304 buffer, verified old-hash flush,
-- new HTTP200 sample, and rollback of a simulated failed catalog publication.
BEGIN;
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location),status='AVAILABLE';
INSERT INTO ndw_snapshot_checkpoint(source_name,source_sha256)
VALUES ('ndw-locations',repeat('a',64));
INSERT INTO snapshot_unchanged_hour
(source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
VALUES ('ndw-locations','2026-10-24 07:00Z',repeat('a',64),2,
'2026-10-24 07:05Z','2026-10-24 07:10Z');
SAVEPOINT before_failed_import;
SELECT * FROM snapshot_flush_unchanged_hour(repeat('a',64));
UPDATE evse SET status='CHARGING';
UPDATE ndw_snapshot_checkpoint SET source_sha256=repeat('b',64) WHERE source_name='ndw-locations';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:15Z');
DO $assert$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM evse_snapshot_active_hour
 WHERE category_counts[1]=2 AND category_counts[2]=1
  AND unchanged_count=2 AND changed_count=1)
 THEN RAISE EXCEPTION 'incorrect 304 then 200 accumulation'; END IF;
END $assert$;
-- Simulate a failure after all counters and catalogue updates, before commit.
ROLLBACK TO SAVEPOINT before_failed_import;
DO $assert$
BEGIN
 IF (SELECT poll_count FROM snapshot_unchanged_hour WHERE source_name='ndw-locations')<>2
 THEN RAISE EXCEPTION 'rollback lost 304 buffer'; END IF;
 IF (SELECT source_sha256 FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations')<>repeat('a',64)
 THEN RAISE EXCEPTION 'rollback kept new source SHA'; END IF;
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour)
 THEN RAISE EXCEPTION 'rollback kept uncommitted counters'; END IF;
 IF EXISTS(SELECT 1 FROM evse WHERE status<>'AVAILABLE')
 THEN RAISE EXCEPTION 'rollback kept new catalogue status'; END IF;
END $assert$;
-- Retry the same transition in the same isolated transaction.
SELECT * FROM snapshot_flush_unchanged_hour(repeat('a',64));
UPDATE evse SET status='CHARGING';
UPDATE ndw_snapshot_checkpoint SET source_sha256=repeat('b',64) WHERE source_name='ndw-locations';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:15Z');
DO $assert$
BEGIN
 IF EXISTS(SELECT 1 FROM snapshot_unchanged_hour WHERE source_name='ndw-locations')
 THEN RAISE EXCEPTION 'retry did not consume buffer'; END IF;
 IF NOT EXISTS(SELECT 1 FROM evse_snapshot_active_hour
 WHERE category_counts[1]=2 AND category_counts[2]=1
 AND unchanged_count=2 AND changed_count=1)
 THEN RAISE EXCEPTION 'retry lost counts or doubled 200 sample'; END IF;
END $assert$;
ROLLBACK;
