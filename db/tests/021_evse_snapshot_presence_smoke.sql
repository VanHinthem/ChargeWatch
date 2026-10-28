\set ON_ERROR_STOP on
BEGIN;
-- The compact-statistics isolated schema intentionally starts minimal.
ALTER TABLE charging_location ADD COLUMN is_active boolean NOT NULL DEFAULT true;
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location),status='AVAILABLE';
INSERT INTO evse(location_id,status,is_active)
SELECT min(id),'AVAILABLE',false FROM charging_location;

-- Inactive historical asset must not be sampled as current capacity.
SELECT * FROM snapshot_record_changed_hour('2026-10-29 07:05Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse_snapshot_active_hour)
    <> (SELECT count(*) FROM evse WHERE is_active) THEN
   RAISE EXCEPTION 'inactive EVSE counted by changed-feed statistics';
 END IF;
END $verify$;

-- The present set is a session-local, indexed set of resolved EVSE row IDs.
CREATE TEMP TABLE stage_present_evse_ids ON COMMIT DROP AS
SELECT id FROM evse WHERE is_active;
CREATE UNIQUE INDEX ON stage_present_evse_ids(id);

-- First and second missing verified HTTP 200: retain current status.
DELETE FROM stage_present_evse_ids;
SELECT * FROM reconcile_evse_snapshot_presence('2026-10-29 08:00Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse WHERE is_active)<>1
 OR (SELECT count(*) FROM evse WHERE consecutive_missing_snapshots=1)<>1
 THEN RAISE EXCEPTION 'first missing observation should not deactivate'; END IF;
END $verify$;

-- Replaying the same poll must never increase missing evidence.
SELECT * FROM reconcile_evse_snapshot_presence('2026-10-29 08:00Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse WHERE consecutive_missing_snapshots=1)<>1
 THEN RAISE EXCEPTION 'replay advanced missing evidence'; END IF;
END $verify$;

SELECT * FROM reconcile_evse_snapshot_presence('2026-10-29 08:05Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse WHERE is_active)<>1
 OR (SELECT count(*) FROM evse WHERE consecutive_missing_snapshots=2)<>1
 THEN RAISE EXCEPTION 'second missing observation should not deactivate'; END IF;
END $verify$;

SELECT * FROM reconcile_evse_snapshot_presence('2026-10-29 08:10Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse WHERE is_active)<>0
 OR (SELECT count(*) FROM evse WHERE consecutive_missing_snapshots=3)<>1
 THEN RAISE EXCEPTION 'third missing observation must deactivate'; END IF;
END $verify$;

-- Reappearing EVSE reactivates, without losing historical rows.
INSERT INTO stage_present_evse_ids(id)
SELECT id FROM evse WHERE consecutive_missing_snapshots=3;
SELECT * FROM reconcile_evse_snapshot_presence('2026-10-29 08:15Z');
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse WHERE is_active)<>1
 OR (SELECT count(*) FROM evse WHERE consecutive_missing_snapshots<>0)<>0
 OR (SELECT count(*) FROM evse WHERE first_missing_at IS NOT NULL)<>0
 THEN RAISE EXCEPTION 'returning EVSE not reactivated cleanly'; END IF;
END $verify$;
ROLLBACK;
