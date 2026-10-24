-- Isolated PostgreSQL 18 CI database only. Exercise the ACTUAL HTTP200
-- set-based function at representative scale; rollback all synthetic assets.
\set ON_ERROR_STOP on
\timing on
BEGIN;
-- The base CI fixtures have IDs 1. Generate 20k extra EVSE and 8k locations.
INSERT INTO charging_location(id) OVERRIDING SYSTEM VALUE
SELECT g FROM generate_series(100000,107999) AS g;
INSERT INTO evse(id,location_id,status) OVERRIDING SYSTEM VALUE
SELECT g,100000+((g-200000)%8000),
  CASE g%5 WHEN 0 THEN 'AVAILABLE' WHEN 1 THEN 'CHARGING'
    WHEN 2 THEN 'BLOCKED' WHEN 3 THEN 'INOPERATIVE' ELSE 'UNKNOWN' END
FROM generate_series(200000,219999) AS g;
SELECT pg_current_wal_lsn() AS start_lsn \gset
SELECT clock_timestamp() AS start_clock \gset
SELECT * FROM snapshot_record_changed_hour('2026-10-24 07:05Z');
SELECT pg_current_wal_lsn() AS end_lsn \gset
SELECT clock_timestamp() AS end_clock \gset
SELECT
 pg_size_pretty(pg_wal_lsn_diff(:'end_lsn',:'start_lsn')) AS wal_one_changed_feed,
 round(pg_wal_lsn_diff(:'end_lsn',:'start_lsn') /
  (SELECT count(*) FROM evse_snapshot_active_hour UNION ALL
   SELECT count(*) FROM location_snapshot_active_hour LIMIT 1),1) AS sample_wal_bytes_per_evse,
 round(extract(epoch FROM :'end_clock'::timestamptz-:'start_clock'::timestamptz)*1000,1)
 AS elapsed_ms,
 (SELECT count(*) FROM evse_snapshot_active_hour) AS evse_rows,
 (SELECT count(*) FROM location_snapshot_active_hour) AS location_rows;
DO $verify$
BEGIN
 IF (SELECT count(*) FROM evse_snapshot_active_hour)<>(SELECT count(*) FROM evse)
 OR (SELECT count(*) FROM location_snapshot_active_hour)<>(SELECT count(*) FROM charging_location)
 THEN RAISE EXCEPTION 'changed-feed load lost entities'; END IF;
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour WHERE changed_count<>1 OR
 (SELECT sum(v) FROM unnest(category_counts) v)<>1)
 THEN RAISE EXCEPTION 'changed-feed load produced incorrect EVSE counters'; END IF;
END $verify$;
ROLLBACK;
