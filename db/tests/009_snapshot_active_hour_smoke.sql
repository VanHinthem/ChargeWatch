\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM schema_migration WHERE version='009_snapshot_active_hour') THEN
  RAISE EXCEPTION 'missing migration 009';
 END IF;
END $$;

-- Simulates the single serialized collector's atomic claim-and-update transaction.
-- A duplicate ID or an older observed time must not claim again.
INSERT INTO snapshot_poll_checkpoint(source_name,last_poll_id,last_observed_at,last_quality)
VALUES ('ndw-locations-ci','poll-A','2026-10-24 07:05Z','changed_feed')
ON CONFLICT (source_name) DO UPDATE SET
 last_poll_id=excluded.last_poll_id,
 last_observed_at=excluded.last_observed_at,
 last_quality=excluded.last_quality,
 accepted_poll_count=snapshot_poll_checkpoint.accepted_poll_count+1
WHERE excluded.last_observed_at>snapshot_poll_checkpoint.last_observed_at
RETURNING accepted_poll_count;

DO $$
DECLARE claimed integer;
BEGIN
 WITH attempt AS (
 INSERT INTO snapshot_poll_checkpoint(source_name,last_poll_id,last_observed_at,last_quality)
 VALUES ('ndw-locations-ci','poll-A','2026-10-24 07:05Z','changed_feed')
 ON CONFLICT (source_name) DO UPDATE SET
  last_poll_id=excluded.last_poll_id,
  last_observed_at=excluded.last_observed_at,
  accepted_poll_count=snapshot_poll_checkpoint.accepted_poll_count+1
 WHERE excluded.last_observed_at>snapshot_poll_checkpoint.last_observed_at
 RETURNING 1
 ) SELECT count(*) INTO claimed FROM attempt;
 IF claimed<>0 THEN RAISE EXCEPTION 'duplicate poll counted'; END IF;
END $$;

WITH target AS (SELECT id FROM evse ORDER BY id LIMIT 1)
INSERT INTO evse_snapshot_active_hour(evse_id,hour_start_utc,category_counts,changed_count)
SELECT id,'2026-10-24 07:00Z',ARRAY[1,0,0,0,0,0,0,0,0],1 FROM target
ON CONFLICT(evse_id) DO NOTHING;

DO $$
DECLARE n integer;
BEGIN
 SELECT count(*) INTO n FROM evse_snapshot_active_hour WHERE array_length(category_counts,1)<>9;
 IF n<>0 THEN RAISE EXCEPTION 'invalid EVSE buckets'; END IF;
END $$;
ROLLBACK;
