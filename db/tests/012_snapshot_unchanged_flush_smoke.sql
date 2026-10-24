\set ON_ERROR_STOP on
BEGIN;
-- Existing CI bootstrap creates one location/EVSE; attach it to a location.
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location),status='AVAILABLE';
INSERT INTO charging_location DEFAULT VALUES;
INSERT INTO evse(location_id,status)
SELECT min(id),'CHARGING' FROM charging_location;
INSERT INTO ndw_snapshot_checkpoint(source_name,source_sha256)
VALUES ('ndw-locations',repeat('a',64));
INSERT INTO snapshot_unchanged_hour
(source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
VALUES ('ndw-locations','2026-10-24 07:00Z',repeat('a',64),3,
'2026-10-24 07:05Z','2026-10-24 07:15Z');
-- Must refuse wrong expected snapshot; buffer must remain untouched.
DO $guard$
BEGIN
 BEGIN
  PERFORM * FROM snapshot_flush_unchanged_hour(repeat('b',64));
  RAISE EXCEPTION 'wrong SHA was accepted';
 EXCEPTION WHEN raise_exception THEN
  IF SQLERRM <> 'unchanged-hour snapshot does not match committed catalog' THEN RAISE; END IF;
 END;
 IF (SELECT poll_count FROM snapshot_unchanged_hour WHERE source_name='ndw-locations')<>3 THEN
   RAISE EXCEPTION 'wrong-SHA attempt changed pending counts';
 END IF;
END $guard$;
-- A correct flush transforms three identical 304 polls into three observations
-- per EVSE and location, and consumes exactly one pending source row.
SELECT * FROM snapshot_flush_unchanged_hour(repeat('a',64));
DO $verify$
BEGIN
 IF EXISTS(SELECT 1 FROM snapshot_unchanged_hour WHERE source_name='ndw-locations') THEN
  RAISE EXCEPTION 'flush left pending row';
 END IF;
 IF (SELECT count(*) FROM evse_snapshot_active_hour)<>(SELECT count(*) FROM evse) THEN
  RAISE EXCEPTION 'EVSE flush incomplete';
 END IF;
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour WHERE unchanged_count<>3
    OR changed_count<>0 OR (SELECT sum(v) FROM unnest(category_counts) AS v)<>3) THEN
  RAISE EXCEPTION 'EVSE flush counts wrong';
 END IF;
 IF EXISTS(SELECT 1 FROM location_snapshot_active_hour WHERE unchanged_count<>3
    OR changed_count<>0 OR (SELECT sum(v) FROM unnest(category_counts) AS v)<>3) THEN
  RAISE EXCEPTION 'location flush counts wrong';
 END IF;
END $verify$;
-- Re-running with no buffer must not duplicate counts.
SELECT * FROM snapshot_flush_unchanged_hour(repeat('a',64));
DO $verify$
BEGIN
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour WHERE unchanged_count<>3) THEN
  RAISE EXCEPTION 'repeat flush double counted';
 END IF;
END $verify$;
ROLLBACK;
