\set ON_ERROR_STOP on
BEGIN;
INSERT INTO snapshot_poll_checkpoint(source_name,last_poll_id,last_observed_at,last_quality)
VALUES ('ndw-locations','200-hash-638969760000000000','2026-10-24 08:00Z','changed_feed');
-- Exact replay of the original verified response must not change counters.
INSERT INTO snapshot_poll_checkpoint(source_name,last_poll_id,last_observed_at,last_quality)
VALUES ('ndw-locations','200-hash-638969760000000000','2026-10-24 08:00Z','changed_feed')
ON CONFLICT(source_name) DO UPDATE SET
 last_poll_id=excluded.last_poll_id,last_observed_at=excluded.last_observed_at,
 last_quality=excluded.last_quality,
 accepted_poll_count=snapshot_poll_checkpoint.accepted_poll_count+1
WHERE excluded.last_observed_at>snapshot_poll_checkpoint.last_observed_at
 AND excluded.last_poll_id<>snapshot_poll_checkpoint.last_poll_id;
DO $check$
BEGIN
 IF (SELECT accepted_poll_count FROM snapshot_poll_checkpoint WHERE source_name='ndw-locations')<>1
 THEN RAISE EXCEPTION 'same-response replay counted twice'; END IF;
END $check$;
ROLLBACK;
