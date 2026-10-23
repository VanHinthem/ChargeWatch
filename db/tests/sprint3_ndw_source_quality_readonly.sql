-- ChargeWatch Sprint 3 source-quality audit. Strictly read-only.
-- Run against chargewatch_benchmark (national snapshot); not statistics_test.
-- No temporary tables, extensions, mutations or schema migrations.
SET TRANSACTION READ ONLY;
WITH base AS (
  SELECT e.id,e.location_id,e.status,e.source_last_updated,
         CASE upper(btrim(e.status))
           WHEN 'AVAILABLE' THEN 'available'
           WHEN 'CHARGING' THEN 'occupied'
           WHEN 'RESERVED' THEN 'occupied'
           WHEN 'BLOCKED' THEN 'occupied'
           WHEN 'INOPERATIVE' THEN 'fault'
           WHEN 'OUTOFORDER' THEN 'fault'
           ELSE 'unknown' END AS status_class
  FROM evse e
), by_location AS (
  SELECT location_id,count(*) AS evses,
         count(*) FILTER (WHERE source_last_updated IS NULL) AS missing_timestamps,
         count(DISTINCT source_last_updated) AS distinct_timestamps,
         count(*) FILTER (WHERE status_class='unknown') AS unknown_evses,
         count(*) FILTER (WHERE status_class='available') AS free_evses,
         count(*) FILTER (WHERE status_class='fault') AS fault_evses,
         min(source_last_updated) AS oldest,
         max(source_last_updated) AS newest
  FROM base GROUP BY location_id
)
SELECT
  (SELECT count(*) FROM charging_location) AS locations_total,
  (SELECT count(*) FROM base) AS evses_total,
  (SELECT count(*) FROM base WHERE source_last_updated IS NULL) AS evses_missing_timestamp,
  (SELECT count(*) FROM base WHERE status_class='unknown') AS evses_unknown_status,
  (SELECT count(*) FROM base WHERE status_class='available') AS evses_available,
  (SELECT count(*) FROM base WHERE status_class='occupied') AS evses_occupied,
  (SELECT count(*) FROM base WHERE status_class='fault') AS evses_fault,
  (SELECT count(*) FROM by_location WHERE evses>=2) AS multi_evse_locations,
  (SELECT count(*) FROM by_location WHERE missing_timestamps=0 AND distinct_timestamps=1) AS all_evse_timestamps_aligned,
  (SELECT count(*) FROM by_location WHERE missing_timestamps=0 AND distinct_timestamps=1 AND unknown_evses=0) AS aligned_known_status_locations,
  (SELECT count(*) FROM by_location WHERE missing_timestamps=0 AND distinct_timestamps=1 AND unknown_evses=0 AND evses>=2) AS aligned_known_multi_evse_locations,
  (SELECT count(*) FROM by_location WHERE free_evses>0) AS demonstrably_free_locations,
  (SELECT count(*) FROM by_location WHERE free_evses=0 AND unknown_evses=0) AS demonstrably_zero_free_locations,
  (SELECT count(*) FROM by_location WHERE free_evses=0 AND unknown_evses>0) AS indeterminate_zero_free_locations;

WITH age AS (
  SELECT EXTRACT(EPOCH FROM (statement_timestamp()-source_last_updated))/3600 AS hours_old
  FROM evse
  WHERE source_last_updated IS NOT NULL
)
SELECT count(*) AS timestamped_evses,
       count(*) FILTER (WHERE hours_old<0) AS future_timestamp_evses,
       count(*) FILTER (WHERE hours_old BETWEEN 0 AND 1) AS updated_within_1h,
       count(*) FILTER (WHERE hours_old BETWEEN 0 AND 24) AS updated_within_24h,
       count(*) FILTER (WHERE hours_old BETWEEN 0 AND 168) AS updated_within_7d,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY hours_old)::numeric,2) AS median_hours_old,
       round(percentile_cont(0.9) WITHIN GROUP (ORDER BY hours_old)::numeric,2) AS p90_hours_old,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY hours_old)::numeric,2) AS p99_hours_old
FROM age;

WITH per_location AS (
 SELECT location_id,count(*) AS evses,
   count(*) FILTER (WHERE source_last_updated IS NULL) AS missing_ts,
   count(DISTINCT source_last_updated) AS distinct_ts,
   count(*) FILTER (WHERE upper(btrim(status)) NOT IN ('AVAILABLE','CHARGING','RESERVED','BLOCKED','INOPERATIVE','OUTOFORDER')) AS unknown_states
 FROM evse GROUP BY location_id
)
SELECT CASE WHEN evses=1 THEN '1' WHEN evses=2 THEN '2'
            WHEN evses BETWEEN 3 AND 4 THEN '3-4'
            WHEN evses BETWEEN 5 AND 10 THEN '5-10' ELSE '11+' END AS evse_count_band,
       count(*) AS locations,
       count(*) FILTER (WHERE missing_ts=0 AND distinct_ts=1) AS timestamps_aligned,
       count(*) FILTER (WHERE missing_ts=0 AND distinct_ts=1 AND unknown_states=0) AS aligned_known,
       count(*) FILTER (WHERE missing_ts>0) AS timestamp_missing,
       count(*) FILTER (WHERE distinct_ts>1) AS timestamps_differ
FROM per_location GROUP BY 1 ORDER BY min(evses);

SELECT upper(btrim(status)) AS raw_status, count(*) AS evses
FROM evse GROUP BY 1 ORDER BY evses DESC,raw_status;

SELECT max(completed_at) AS most_recent_completed_import,
       count(*) AS completed_imports
FROM import_run WHERE status='completed';
