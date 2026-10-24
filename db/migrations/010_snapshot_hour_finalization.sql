-- Finalize one completed UTC hour into the local ISO weekday/hour EWMA slot.
-- Exactly one currently active hour row per asset. Repeated local 02:00 at
-- autumn DST is two UTC hours, both eligible observations of one local slot.
-- Call within the same transaction that claims/accumulates the next poll.
CREATE OR REPLACE FUNCTION snapshot_finalize_hours(
  p_before_utc timestamptz,
  p_local_weekday integer,
  p_local_hour integer,
  p_alpha double precision
) RETURNS TABLE(evse_finalized bigint, location_finalized bigint)
LANGUAGE plpgsql AS $func$
DECLARE
  n_evse bigint;
  n_location bigint;
BEGIN
  IF p_local_weekday NOT BETWEEN 1 AND 7 OR p_local_hour NOT BETWEEN 0 AND 23
     OR p_alpha <= 0 OR p_alpha > 1 THEN
    RAISE EXCEPTION 'invalid slot or EWMA alpha';
  END IF;
  -- Caller supplies one UTC hour at a time, with its Amsterdam local slot.
  -- Profiles already finalized cannot be finalized twice: source rows are deleted
  -- by the same transaction, and advisory serialization is required from caller.
  WITH eligible AS (
    SELECT a.* FROM evse_snapshot_active_hour a
    WHERE a.hour_start_utc=p_before_utc
      AND a.changed_count+a.unchanged_count>0
    FOR UPDATE
  ), upsert AS (
    INSERT INTO evse_snapshot_weekday_profile
      (evse_id,iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at)
    SELECT evse_id,p_local_weekday,
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN
        eligible.category_counts[c]::real / (eligible.changed_count+eligible.unchanged_count)
        ELSE NULL::real END
        FROM generate_series(1,9) c CROSS JOIN generate_series(0,23) h ORDER BY c,h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.changed_count+eligible.unchanged_count ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.changed_count ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.unchanged_count ELSE 0 END FROM generate_series(0,23) h),
      p_before_utc + interval '1 hour'
    FROM eligible
    ON CONFLICT(evse_id,iso_weekday) DO UPDATE SET
      category_ewma=ARRAY(
        SELECT CASE WHEN h=p_local_hour THEN
          (CASE WHEN evse_snapshot_weekday_profile.category_ewma[(c-1)*24+h+1] IS NULL
           THEN excluded.category_ewma[(c-1)*24+h+1]
           ELSE ((1-p_alpha)*evse_snapshot_weekday_profile.category_ewma[(c-1)*24+h+1]
             + p_alpha*excluded.category_ewma[(c-1)*24+h+1])::real END)
          ELSE evse_snapshot_weekday_profile.category_ewma[(c-1)*24+h+1] END
        FROM generate_series(1,9) c CROSS JOIN generate_series(0,23) h ORDER BY c,h),
      sample_count=ARRAY(
        SELECT evse_snapshot_weekday_profile.sample_count[h+1]+excluded.sample_count[h+1]
        FROM generate_series(0,23) h),
      changed_feed_count=ARRAY(
        SELECT evse_snapshot_weekday_profile.changed_feed_count[h+1]+excluded.changed_feed_count[h+1]
        FROM generate_series(0,23) h),
      unchanged_feed_count=ARRAY(
        SELECT evse_snapshot_weekday_profile.unchanged_feed_count[h+1]+excluded.unchanged_feed_count[h+1]
        FROM generate_series(0,23) h),
      last_sample_at=excluded.last_sample_at
    RETURNING evse_id
  ), removed AS (
    DELETE FROM evse_snapshot_active_hour a
    USING upsert u WHERE a.evse_id=u.evse_id AND a.hour_start_utc=p_before_utc
    RETURNING a.evse_id
  )
  SELECT count(*) INTO n_evse FROM removed;

  WITH eligible AS (
    SELECT a.* FROM location_snapshot_active_hour a
    WHERE a.hour_start_utc=p_before_utc
      AND a.changed_count+a.unchanged_count>0
    FOR UPDATE
  ), upsert AS (
    INSERT INTO location_snapshot_weekday_profile
      (location_id,iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at)
    SELECT location_id,p_local_weekday,
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN
        eligible.category_counts[c]::real/(eligible.changed_count+eligible.unchanged_count)
        ELSE NULL::real END FROM generate_series(1,5) c CROSS JOIN generate_series(0,23) h ORDER BY c,h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.changed_count+eligible.unchanged_count ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.changed_count ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.unchanged_count ELSE 0 END FROM generate_series(0,23) h),
      p_before_utc + interval '1 hour'
    FROM eligible
    ON CONFLICT(location_id,iso_weekday) DO UPDATE SET
      category_ewma=ARRAY(
        SELECT CASE WHEN h=p_local_hour THEN
          (CASE WHEN location_snapshot_weekday_profile.category_ewma[(c-1)*24+h+1] IS NULL
            THEN excluded.category_ewma[(c-1)*24+h+1]
            ELSE ((1-p_alpha)*location_snapshot_weekday_profile.category_ewma[(c-1)*24+h+1]
              + p_alpha*excluded.category_ewma[(c-1)*24+h+1])::real END)
          ELSE location_snapshot_weekday_profile.category_ewma[(c-1)*24+h+1] END
        FROM generate_series(1,5) c CROSS JOIN generate_series(0,23) h ORDER BY c,h),
      sample_count=ARRAY(
        SELECT location_snapshot_weekday_profile.sample_count[h+1]+excluded.sample_count[h+1]
        FROM generate_series(0,23) h),
      changed_feed_count=ARRAY(
        SELECT location_snapshot_weekday_profile.changed_feed_count[h+1]+excluded.changed_feed_count[h+1]
        FROM generate_series(0,23) h),
      unchanged_feed_count=ARRAY(
        SELECT location_snapshot_weekday_profile.unchanged_feed_count[h+1]+excluded.unchanged_feed_count[h+1]
        FROM generate_series(0,23) h),
      last_sample_at=excluded.last_sample_at
    RETURNING location_id
  ), removed AS (
    DELETE FROM location_snapshot_active_hour a
    USING upsert u WHERE a.location_id=u.location_id AND a.hour_start_utc=p_before_utc
    RETURNING a.location_id
  )
  SELECT count(*) INTO n_location FROM removed;
  RETURN QUERY SELECT n_evse,n_location;
END $func$;

GRANT EXECUTE ON FUNCTION snapshot_finalize_hours(timestamptz,integer,integer,double precision)
  TO chargewatch_import;
INSERT INTO schema_migration(version) VALUES ('010_snapshot_hour_finalization')
ON CONFLICT DO NOTHING;
