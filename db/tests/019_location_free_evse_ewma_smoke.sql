\set ON_ERROR_STOP on
BEGIN;
-- A complete known EVSE snapshot counts, UNKNOWN is excluded (never zero).
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location),status='AVAILABLE';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:05Z');
UPDATE evse SET status='UNKNOWN';
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:10Z');
DO $verify$
BEGIN
 IF EXISTS(SELECT 1 FROM location_snapshot_active_hour
  WHERE valid_count_samples<>1 OR excluded_count_samples<>1 OR free_evse_sum<>1 OR active_evse_sum<>1)
 THEN RAISE EXCEPTION 'UNKNOWN poll counted as zero or complete count omitted'; END IF;
END $verify$;
SELECT * FROM snapshot_finalize_hours('2026-10-24 07:00Z',6,9,0.05248399218516142);
DO $verify$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM location_snapshot_weekday_profile
  WHERE iso_weekday=6 AND available_evse_ewma[10]=1 AND active_evse_ewma[10]=1
    AND valid_count_samples[10]=1 AND excluded_count_samples[10]=1)
 THEN RAISE EXCEPTION 'count EWMA did not initialize from valid snapshot'; END IF;
END $verify$;
-- Next week's hour has zero free and one active. It must smooth, not reset.
UPDATE evse SET status='CHARGING';
SELECT * FROM snapshot_record_changed_hour('2026-10-31 08:05Z');
SELECT * FROM snapshot_finalize_hours('2026-10-31 08:00Z',6,9,0.05248399218516142);
DO $verify$
DECLARE x real;
BEGIN
 SELECT available_evse_ewma[10] INTO x FROM location_snapshot_weekday_profile WHERE iso_weekday=6;
 IF x IS NULL OR abs(x-(1-0.05248399218516142))>0.0001 THEN
  RAISE EXCEPTION 'free EVSE EWMA smoothing mismatch: %',x;
 END IF;
END $verify$;
ROLLBACK;
