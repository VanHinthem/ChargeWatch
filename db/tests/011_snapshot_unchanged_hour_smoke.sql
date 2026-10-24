\set ON_ERROR_STOP on
BEGIN;
INSERT INTO snapshot_unchanged_hour
 (source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
VALUES ('test','2026-10-24 07:00Z',repeat('a',64),1,'2026-10-24 07:05Z','2026-10-24 07:05Z');
-- A second unchanged observation adds a count and no historical row.
INSERT INTO snapshot_unchanged_hour
 (source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
VALUES ('test','2026-10-24 07:00Z',repeat('a',64),1,'2026-10-24 07:10Z','2026-10-24 07:10Z')
ON CONFLICT(source_name) DO UPDATE SET
 poll_count=snapshot_unchanged_hour.poll_count+excluded.poll_count,
 last_observed_at=excluded.last_observed_at
WHERE snapshot_unchanged_hour.hour_start_utc=excluded.hour_start_utc
  AND snapshot_unchanged_hour.snapshot_sha256=excluded.snapshot_sha256
  AND excluded.first_observed_at>snapshot_unchanged_hour.last_observed_at;
DO $test$
BEGIN
 IF (SELECT poll_count FROM snapshot_unchanged_hour WHERE source_name='test')<>2
 THEN RAISE EXCEPTION 'unchanged observation count failed'; END IF;
 IF (SELECT count(*) FROM snapshot_unchanged_hour WHERE source_name='test')<>1
 THEN RAISE EXCEPTION 'unexpected poll history'; END IF;
END $test$;
SAVEPOINT crash_before_commit;
UPDATE snapshot_unchanged_hour SET poll_count=poll_count+1 WHERE source_name='test';
ROLLBACK TO SAVEPOINT crash_before_commit;
DO $test$
BEGIN
 IF (SELECT poll_count FROM snapshot_unchanged_hour WHERE source_name='test')<>2
 THEN RAISE EXCEPTION 'rollback did not preserve count'; END IF;
END $test$;
-- New UTC hour and changed snapshot must not overwrite unflushed evidence.
DO $guard$
DECLARE n integer;
BEGIN
 WITH attempt AS (
 INSERT INTO snapshot_unchanged_hour
 (source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
 VALUES ('test','2026-10-24 08:00Z',repeat('a',64),1,'2026-10-24 08:05Z','2026-10-24 08:05Z')
 ON CONFLICT(source_name) DO UPDATE SET
   poll_count=snapshot_unchanged_hour.poll_count+excluded.poll_count,
   last_observed_at=excluded.last_observed_at
 WHERE snapshot_unchanged_hour.hour_start_utc=excluded.hour_start_utc
   AND snapshot_unchanged_hour.snapshot_sha256=excluded.snapshot_sha256
 RETURNING 1
 ) SELECT count(*) INTO n FROM attempt;
 IF n<>0 THEN RAISE EXCEPTION 'unflushed hour was overwritten'; END IF;
 WITH attempt AS (
 INSERT INTO snapshot_unchanged_hour
 (source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
 VALUES ('test','2026-10-24 07:00Z',repeat('b',64),1,'2026-10-24 07:15Z','2026-10-24 07:15Z')
 ON CONFLICT(source_name) DO UPDATE SET
   poll_count=snapshot_unchanged_hour.poll_count+excluded.poll_count,
   last_observed_at=excluded.last_observed_at
 WHERE snapshot_unchanged_hour.hour_start_utc=excluded.hour_start_utc
   AND snapshot_unchanged_hour.snapshot_sha256=excluded.snapshot_sha256
 RETURNING 1
 ) SELECT count(*) INTO n FROM attempt;
 IF n<>0 THEN RAISE EXCEPTION 'unflushed source snapshot was overwritten'; END IF;
 IF (SELECT poll_count FROM snapshot_unchanged_hour WHERE source_name='test')<>2
 THEN RAISE EXCEPTION 'failed conflict attempts modified durable evidence'; END IF;
END $guard$;
ROLLBACK;
