\set ON_ERROR_STOP on
BEGIN;
-- Multi-connector independence is guaranteed by counting EVSE rows, never connectors.
-- Current isolated fixture supports a minimal charging_location/evse catalog.
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location),status='AVAILABLE';
INSERT INTO evse(location_id,status) SELECT min(id),'OUTOFORDER' FROM charging_location;
INSERT INTO evse(location_id,status) SELECT min(id),'PLANNED' FROM charging_location;
INSERT INTO evse(location_id,status) SELECT min(id),'REMOVED' FROM charging_location;
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:05Z');
DO $verify$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM location_snapshot_active_hour
   WHERE free_evse_sum=1 AND active_evse_sum=2 AND valid_count_samples=1
     AND excluded_count_samples=0)
 THEN RAISE EXCEPTION 'defect/planned/removed count semantics mismatch'; END IF;
END $verify$;
-- One independent 304 sequence represents three accepted observations, not three rows.
INSERT INTO ndw_snapshot_checkpoint(source_name,source_sha256)
VALUES('ndw-locations',repeat('e',64));
INSERT INTO snapshot_unchanged_hour
(source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
VALUES('ndw-locations','2026-10-24 07:00Z',repeat('e',64),3,
'2026-10-24 07:15Z','2026-10-24 07:25Z');
SELECT * FROM snapshot_flush_unchanged_hour(repeat('e',64));
DO $verify$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM location_snapshot_active_hour
   WHERE free_evse_sum=4 AND active_evse_sum=8 AND valid_count_samples=4
     AND excluded_count_samples=0 AND changed_count=1 AND unchanged_count=3)
 THEN RAISE EXCEPTION '304 count multiplication or capacity incorrect'; END IF;
END $verify$;
SELECT * FROM snapshot_flush_unchanged_hour(repeat('e',64));
DO $verify$
BEGIN
 IF EXISTS(SELECT 1 FROM location_snapshot_active_hour WHERE valid_count_samples<>4)
 THEN RAISE EXCEPTION 'duplicate 304 flush counted again'; END IF;
END $verify$;
ROLLBACK;
