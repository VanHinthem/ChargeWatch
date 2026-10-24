-- ISOLATED CI DATABASE ONLY. Synthetic current-hour counter update WAL and throughput.
\set ON_ERROR_STOP on
\timing on
DROP TABLE IF EXISTS benchmark_active_evse;
DROP TABLE IF EXISTS benchmark_active_location;
CREATE TABLE benchmark_active_evse (
 id bigint PRIMARY KEY, hour_start_utc timestamptz NOT NULL,
 category_counts integer[] NOT NULL, changed_count integer NOT NULL,
 unchanged_count integer NOT NULL
);
CREATE TABLE benchmark_active_location (LIKE benchmark_active_evse INCLUDING ALL);
INSERT INTO benchmark_active_evse
SELECT g,'2026-10-24 07:00Z',ARRAY[1,0,0,0,0,0,0,0,0],1,0
FROM generate_series(1,20000) g;
INSERT INTO benchmark_active_location
SELECT g,'2026-10-24 07:00Z',ARRAY[1,0,0,0,0],1,0
FROM generate_series(1,8000) g;
SELECT 'active_evse' kind,pg_total_relation_size('benchmark_active_evse') bytes,
 round(pg_total_relation_size('benchmark_active_evse')::numeric/20000,1) bytes_per_row
UNION ALL
SELECT 'active_location',pg_total_relation_size('benchmark_active_location'),
 round(pg_total_relation_size('benchmark_active_location')::numeric/8000,1);
SELECT pg_current_wal_lsn() AS before_wal \gset
-- Simulated unchanged-feed poll: 28k existing rows updated as in SnapshotPollStore.
UPDATE benchmark_active_evse SET
 category_counts=ARRAY(SELECT benchmark_active_evse.category_counts[i]+(CASE WHEN i=1 THEN 1 ELSE 0 END)
 FROM generate_series(1,9) i ORDER BY i),
 unchanged_count=unchanged_count+1;
UPDATE benchmark_active_location SET
 category_counts=ARRAY(SELECT benchmark_active_location.category_counts[i]+(CASE WHEN i=1 THEN 1 ELSE 0 END)
 FROM generate_series(1,5) i ORDER BY i),
 unchanged_count=unchanged_count+1;
SELECT pg_current_wal_lsn() AS after_wal \gset
SELECT pg_size_pretty(pg_wal_lsn_diff(:'after_wal',:'before_wal')) AS wal_for_28k_counter_updates,
 round(pg_wal_lsn_diff(:'after_wal',:'before_wal')/28000,1) AS wal_bytes_per_row,
 round(pg_wal_lsn_diff(:'after_wal',:'before_wal')/28000*284400/1024/1024,1) AS projected_mib_per_national_poll,
 round(pg_wal_lsn_diff(:'after_wal',:'before_wal')/28000*284400*12/1024/1024/1024,2) AS projected_gib_per_12_polls;
-- No production asset tables or content are touched.
DROP TABLE benchmark_active_evse;
DROP TABLE benchmark_active_location;
