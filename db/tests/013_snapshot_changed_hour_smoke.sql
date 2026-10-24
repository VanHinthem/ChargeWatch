\set ON_ERROR_STOP on
BEGIN;
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location), status='AVAILABLE';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:05Z');
DO $verify$
BEGIN
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour
 WHERE changed_count<>1 OR unchanged_count<>0 OR category_counts[1]<>1)
 THEN RAISE EXCEPTION 'changed EVSE category not recorded'; END IF;
 IF EXISTS(SELECT 1 FROM location_snapshot_active_hour
 WHERE changed_count<>1 OR unchanged_count<>0 OR category_counts[1]<>1)
 THEN RAISE EXCEPTION 'changed location category not recorded'; END IF;
END $verify$;
-- Another independently verified 200 in the same hour increments counts.
UPDATE evse SET status='CHARGING';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:10Z');
DO $verify$
BEGIN
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour WHERE changed_count<>2
  OR category_counts[1]<>1 OR category_counts[2]<>1)
 THEN RAISE EXCEPTION 'changed EVSE counters did not accumulate'; END IF;
 IF EXISTS(SELECT 1 FROM location_snapshot_active_hour WHERE changed_count<>2
  OR category_counts[1]<>1 OR category_counts[2]<>1)
 THEN RAISE EXCEPTION 'changed location counters did not accumulate'; END IF;
END $verify$;
ROLLBACK;
