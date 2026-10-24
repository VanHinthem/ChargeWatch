-- ISOLATED CI ONLY: compare twelve per-poll writes against one hourly
-- batched update representing the same twelve unchanged-feed observations.
-- This is a cost study, not yet a durable crash-safe ingestion strategy.
\set ON_ERROR_STOP on
\timing on
DROP TABLE IF EXISTS benchmark_hourly_polls;
DROP TABLE IF EXISTS benchmark_hourly_batch;
CREATE TABLE benchmark_hourly_polls(
  id bigint PRIMARY KEY,category_counts integer[] NOT NULL,
  changed_count integer NOT NULL,unchanged_count integer NOT NULL
);
CREATE TABLE benchmark_hourly_batch(LIKE benchmark_hourly_polls INCLUDING ALL);
INSERT INTO benchmark_hourly_polls
SELECT g,ARRAY[1,0,0,0,0,0,0,0,0],1,0 FROM generate_series(1,20000) g;
INSERT INTO benchmark_hourly_batch SELECT * FROM benchmark_hourly_polls;

SELECT pg_current_wal_lsn() AS poll_wal_before \gset
DO $$
BEGIN
  FOR i IN 1..12 LOOP
    UPDATE benchmark_hourly_polls
    SET category_counts[1]=category_counts[1]+1,
        unchanged_count=unchanged_count+1;
  END LOOP;
END $$;
SELECT pg_current_wal_lsn() AS poll_wal_after \gset
SELECT pg_current_wal_lsn() AS batch_wal_before \gset
UPDATE benchmark_hourly_batch
SET category_counts[1]=category_counts[1]+12,
    unchanged_count=unchanged_count+12;
SELECT pg_current_wal_lsn() AS batch_wal_after \gset
SELECT
  pg_size_pretty(pg_wal_lsn_diff(:'poll_wal_after',:'poll_wal_before')) AS wal_12_polls,
  pg_size_pretty(pg_wal_lsn_diff(:'batch_wal_after',:'batch_wal_before')) AS wal_one_batch,
  round(pg_wal_lsn_diff(:'poll_wal_after',:'poll_wal_before') /
     nullif(pg_wal_lsn_diff(:'batch_wal_after',:'batch_wal_before'),0),2) AS wal_reduction_factor;
DO $$
BEGIN
 IF EXISTS (
   SELECT 1 FROM benchmark_hourly_polls p
   JOIN benchmark_hourly_batch b ON b.id=p.id
   WHERE p.category_counts IS DISTINCT FROM b.category_counts
      OR p.changed_count<>b.changed_count
      OR p.unchanged_count<>b.unchanged_count
 ) THEN RAISE EXCEPTION 'batched counts differ from 12 successful polls'; END IF;
END $$;
DROP TABLE benchmark_hourly_polls;
DROP TABLE benchmark_hourly_batch;
