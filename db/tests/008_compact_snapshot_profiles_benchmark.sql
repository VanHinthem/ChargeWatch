-- ISOLATED TEST DATABASE ONLY.
-- Measures representative row size and update WAL for the candidate weekday layout.
-- This creates/drops benchmark tables and must never be run on the NAS production DB.
\set ON_ERROR_STOP on
\timing on

DROP TABLE IF EXISTS benchmark_evse_snapshot_profile;
DROP TABLE IF EXISTS benchmark_location_snapshot_profile;

CREATE TABLE benchmark_evse_snapshot_profile (
    entity_id bigint NOT NULL,
    iso_weekday smallint NOT NULL,
    category_ewma real[] NOT NULL,
    sample_count integer[] NOT NULL,
    changed_feed_count integer[] NOT NULL,
    unchanged_feed_count integer[] NOT NULL,
    PRIMARY KEY(entity_id,iso_weekday)
);

CREATE TABLE benchmark_location_snapshot_profile (
    entity_id bigint NOT NULL,
    iso_weekday smallint NOT NULL,
    category_ewma real[] NOT NULL,
    sample_count integer[] NOT NULL,
    changed_feed_count integer[] NOT NULL,
    unchanged_feed_count integer[] NOT NULL,
    PRIMARY KEY(entity_id,iso_weekday)
);

-- 20k representative populated rows; random values avoid unrealistic zero-array compression.
INSERT INTO benchmark_evse_snapshot_profile
SELECT g, ((g-1)%7)+1,
       ARRAY(SELECT (random() + g*0)::real FROM generate_series(1,216)),
       array_fill(100,ARRAY[24]),
       array_fill(70,ARRAY[24]),
       array_fill(30,ARRAY[24])
FROM generate_series(1,20000) g;

INSERT INTO benchmark_location_snapshot_profile
SELECT g, ((g-1)%7)+1,
       ARRAY(SELECT (random() + g*0)::real FROM generate_series(1,120)),
       array_fill(100,ARRAY[24]),
       array_fill(70,ARRAY[24]),
       array_fill(30,ARRAY[24])
FROM generate_series(1,20000) g;

SELECT
  'evse' AS kind,
  pg_total_relation_size('benchmark_evse_snapshot_profile') AS bytes_20k,
  round(pg_total_relation_size('benchmark_evse_snapshot_profile')::numeric/20000,1) AS bytes_per_row,
  round(pg_total_relation_size('benchmark_evse_snapshot_profile')::numeric/20000*203500*7/1024/1024/1024,2) AS projected_gib_203500_x7
UNION ALL
SELECT
  'location',
  pg_total_relation_size('benchmark_location_snapshot_profile'),
  round(pg_total_relation_size('benchmark_location_snapshot_profile')::numeric/20000,1),
  round(pg_total_relation_size('benchmark_location_snapshot_profile')::numeric/20000*80900*7/1024/1024/1024,2);

SELECT pg_current_wal_lsn() AS wal_before \gset

-- Simulate one aggregate flush touching today's weekday row.
UPDATE benchmark_evse_snapshot_profile
SET category_ewma[1] = (category_ewma[1] * 0.947516)::real + (0.052484)::real,
    sample_count[1] = sample_count[1] + 1,
    changed_feed_count[1] = changed_feed_count[1] + 1;

UPDATE benchmark_location_snapshot_profile
SET category_ewma[1] = (category_ewma[1] * 0.947516)::real + (0.052484)::real,
    sample_count[1] = sample_count[1] + 1,
    changed_feed_count[1] = changed_feed_count[1] + 1;

SELECT pg_current_wal_lsn() AS wal_after \gset
SELECT pg_size_pretty(pg_wal_lsn_diff(:'wal_after', :'wal_before')) AS wal_for_40k_updates;

DROP TABLE benchmark_evse_snapshot_profile;
DROP TABLE benchmark_location_snapshot_profile;
