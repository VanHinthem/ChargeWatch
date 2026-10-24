\set ON_ERROR_STOP on
-- A committed pending buffer survives closing the connection.
INSERT INTO ndw_snapshot_checkpoint(source_name,source_sha256)
VALUES ('ndw-locations',repeat('c',64))
ON CONFLICT(source_name) DO UPDATE SET source_sha256=excluded.source_sha256;
UPDATE evse SET location_id=(SELECT min(id) FROM charging_location),status='AVAILABLE';
INSERT INTO snapshot_unchanged_hour
 (source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
VALUES ('ndw-locations','2026-10-24 07:00Z',repeat('c',64),2,
 '2026-10-24 07:05Z','2026-10-24 07:10Z');
