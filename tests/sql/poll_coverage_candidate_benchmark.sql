-- Issue #72: representative candidate-projection microbenchmark.
-- Executes only against isolated CI PostgreSQL. No production tables are read/written.
-- This compares the candidate projection itself, NOT total collector throughput.
\timing on
BEGIN;
CREATE TEMP TABLE bench_poll_eligible ON COMMIT DROP AS
SELECT id::bigint AS evse_id, 'available'::text AS status_class,
       '2026-10-25 08:00:00.100+00'::timestamptz AS start_at,
       '2026-10-25 08:10:00.700+00'::timestamptz AS end_at
FROM generate_series(1,205000) id;

-- Prior implementation repeated this projection for probe and INSERT.
\echo 'BEFORE: first candidate projection (205000 rows)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT evse_id,h.hour_start_utc,
       sum((extract(epoch FROM
         least(end_at,h.hour_start_utc+interval '1 hour')
         -greatest(start_at,h.hour_start_utc))*1000000)::bigint) microseconds
FROM bench_poll_eligible
CROSS JOIN LATERAL generate_series(
 date_trunc('hour',start_at),date_trunc('hour',end_at),interval '1 hour'
) h(hour_start_utc)
GROUP BY evse_id,h.hour_start_utc;

\echo 'BEFORE: second candidate projection (repeated independently)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT evse_id,h.hour_start_utc,
       sum((extract(epoch FROM
         least(end_at,h.hour_start_utc+interval '1 hour')
         -greatest(start_at,h.hour_start_utc))*1000000)::bigint) microseconds
FROM bench_poll_eligible
CROSS JOIN LATERAL generate_series(
 date_trunc('hour',start_at),date_trunc('hour',end_at),interval '1 hour'
) h(hour_start_utc)
GROUP BY evse_id,h.hour_start_utc;

\echo 'AFTER: materialize once and reuse'
EXPLAIN (ANALYZE, BUFFERS)
CREATE TEMP TABLE bench_poll_proposed ON COMMIT DROP AS
SELECT evse_id,h.hour_start_utc,
       sum((extract(epoch FROM
         least(end_at,h.hour_start_utc+interval '1 hour')
         -greatest(start_at,h.hour_start_utc))*1000000)::bigint) microseconds
FROM bench_poll_eligible
CROSS JOIN LATERAL generate_series(
 date_trunc('hour',start_at),date_trunc('hour',end_at),interval '1 hour'
) h(hour_start_utc)
GROUP BY evse_id,h.hour_start_utc;
\echo 'AFTER: first reuse'
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(microseconds) FROM bench_poll_proposed;
\echo 'AFTER: second reuse'
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM bench_poll_proposed;
ROLLBACK;
