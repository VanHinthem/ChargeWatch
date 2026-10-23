-- Read-only audit for candidate snapshot coverage. Run on isolated statistics test DB.
BEGIN READ ONLY;
SELECT 'snapshot_checkpoint' AS section, source_name,
       source_sha256, fetched_at, location_count, evse_count
FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations';

SELECT 'coverage_totals' AS section,
       count(*) AS evse_hours,
       count(DISTINCT evse_id) AS evses_with_coverage,
       coalesce(sum(covered_seconds),0) AS cumulative_covered_seconds,
       coalesce(sum(available_seconds),0) AS cumulative_available_seconds,
       count(*) FILTER(WHERE covered_seconds=3600) AS fully_covered_hours,
       count(*) FILTER(WHERE covered_seconds>3600
          OR available_seconds>covered_seconds
          OR covered_seconds<0) AS impossible_rows,
       min(hour_start_utc) AS first_hour, max(hour_start_utc) AS last_hour
FROM evse_poll_hour_coverage;

SELECT 'checkpoint_quality' AS section,
       count(*) AS total,
       count(*) FILTER (WHERE status_class='unknown') AS unknown_status,
       count(*) FILTER (WHERE source_updated_at IS NULL) AS missing_source_time,
       count(*) FILTER (WHERE source_updated_at > observed_at + interval '5 minutes') AS future_source_timestamp,
       count(*) FILTER (WHERE source_updated_at < observed_at - interval '24 hours') AS source_over_24h_old,
       count(*) FILTER (WHERE source_updated_at < observed_at - interval '7 days') AS source_over_7d_old,
       count(*) FILTER (WHERE source_updated_at < observed_at - interval '30 days') AS source_over_30d_old,
       count(*) FILTER (WHERE observed_at >
           (SELECT fetched_at FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'))
           AS checkpoint_ahead_of_feed
FROM evse_poll_checkpoint;

SELECT 'coverage_distribution' AS section,
       count(*) FILTER (WHERE covered_seconds BETWEEN 1 AND 899) AS lt_15m,
       count(*) FILTER (WHERE covered_seconds BETWEEN 900 AND 1799) AS from_15m_to_30m,
       count(*) FILTER (WHERE covered_seconds BETWEEN 1800 AND 2699) AS from_30m_to_45m,
       count(*) FILTER (WHERE covered_seconds BETWEEN 2700 AND 3599) AS from_45m_to_60m,
       count(*) FILTER (WHERE covered_seconds=3600) AS full_hours
FROM evse_poll_hour_coverage;
COMMIT;
