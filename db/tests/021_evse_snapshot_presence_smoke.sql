\\set ON_ERROR_STOP on
BEGIN;
-- Historical rows are retained, but must not count as currently visible capacity.
INSERT INTO charging_location DEFAULT VALUES;
INSERT INTO evse(location_id,status,is_active)
SELECT min(id),'AVAILABLE',false FROM charging_location;
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse WHERE NOT is_active) < 1 THEN
   RAISE EXCEPTION 'missing EVSE presence fixture';
 END IF;
END $verify$;
SELECT * FROM snapshot_record_changed_hour('2026-10-29 07:05Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse_snapshot_active_hour)
    <> (SELECT count(*) FROM evse WHERE is_active) THEN
   RAISE EXCEPTION 'inactive EVSE counted by changed-feed statistics';
 END IF;
 IF EXISTS (
   SELECT 1 FROM evse_snapshot_active_hour a
   JOIN evse e ON e.id=a.evse_id WHERE NOT e.is_active
 ) THEN RAISE EXCEPTION 'inactive EVSE sampled'; END IF;
END $verify$;
ROLLBACK;
