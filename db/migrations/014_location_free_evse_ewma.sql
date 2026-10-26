-- Issue #56: count-based location history alongside existing category probability.
-- Additive only; existing profiles are not backfilled from past percentages.
ALTER TABLE location_snapshot_active_hour
  ADD COLUMN IF NOT EXISTS free_evse_sum bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS active_evse_sum bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS valid_count_samples integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS excluded_count_samples integer NOT NULL DEFAULT 0;
ALTER TABLE location_snapshot_weekday_profile
  ADD COLUMN IF NOT EXISTS available_evse_ewma real[] NOT NULL DEFAULT array_fill(NULL::real,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS active_evse_ewma real[] NOT NULL DEFAULT array_fill(NULL::real,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS valid_count_samples integer[] NOT NULL DEFAULT array_fill(0,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS excluded_count_samples integer[] NOT NULL DEFAULT array_fill(0,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS count_metric_started_at timestamptz,
  ADD COLUMN IF NOT EXISTS count_metric_last_sample_at timestamptz;
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='location_count_arrays_24') THEN
  ALTER TABLE location_snapshot_weekday_profile ADD CONSTRAINT location_count_arrays_24
   CHECK (array_length(available_evse_ewma,1)=24
      AND array_length(active_evse_ewma,1)=24
      AND array_length(valid_count_samples,1)=24
      AND array_length(excluded_count_samples,1)=24);
 END IF;
END $$;
INSERT INTO schema_migration(version) VALUES ('014_location_free_evse_ewma')
ON CONFLICT DO NOTHING;

-- Reinstall live SQL functions: editing earlier migration files alone would not update NAS functions.
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
  IF p_local_weekday <> extract(isodow from p_before_utc AT TIME ZONE 'Europe/Amsterdam')::integer
     OR p_local_hour <> extract(hour from p_before_utc AT TIME ZONE 'Europe/Amsterdam')::integer
     OR p_before_utc <> date_trunc('hour',p_before_utc) THEN
    RAISE EXCEPTION 'local slot must match UTC hour in Europe/Amsterdam';
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
      (location_id,iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at,available_evse_ewma,active_evse_ewma,valid_count_samples,excluded_count_samples,count_metric_started_at,count_metric_last_sample_at)
    SELECT location_id,p_local_weekday,
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN
        eligible.category_counts[c]::real/(eligible.changed_count+eligible.unchanged_count)
        ELSE NULL::real END FROM generate_series(1,5) c CROSS JOIN generate_series(0,23) h ORDER BY c,h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.changed_count+eligible.unchanged_count ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.changed_count ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.unchanged_count ELSE 0 END FROM generate_series(0,23) h),
      p_before_utc + interval '1 hour',
      ARRAY(SELECT CASE WHEN h=p_local_hour AND eligible.valid_count_samples>0 THEN (eligible.free_evse_sum::real/eligible.valid_count_samples) ELSE NULL::real END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour AND eligible.valid_count_samples>0 THEN (eligible.active_evse_sum::real/eligible.valid_count_samples) ELSE NULL::real END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.valid_count_samples ELSE 0 END FROM generate_series(0,23) h),
      ARRAY(SELECT CASE WHEN h=p_local_hour THEN eligible.excluded_count_samples ELSE 0 END FROM generate_series(0,23) h),
      CASE WHEN eligible.valid_count_samples>0 THEN p_before_utc ELSE NULL END,
      CASE WHEN eligible.valid_count_samples>0 THEN p_before_utc+interval '1 hour' ELSE NULL END
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
      last_sample_at=excluded.last_sample_at,
      available_evse_ewma=ARRAY(
        SELECT CASE WHEN h=p_local_hour AND excluded.valid_count_samples[h+1]>0 THEN
          CASE WHEN location_snapshot_weekday_profile.available_evse_ewma[h+1] IS NULL
            THEN excluded.available_evse_ewma[h+1]
            ELSE ((1-p_alpha)*location_snapshot_weekday_profile.available_evse_ewma[h+1]
                   + p_alpha*excluded.available_evse_ewma[h+1])::real END
          ELSE location_snapshot_weekday_profile.available_evse_ewma[h+1] END
        FROM generate_series(0,23) h),
      active_evse_ewma=ARRAY(
        SELECT CASE WHEN h=p_local_hour AND excluded.valid_count_samples[h+1]>0 THEN
          CASE WHEN location_snapshot_weekday_profile.active_evse_ewma[h+1] IS NULL
            THEN excluded.active_evse_ewma[h+1]
            ELSE ((1-p_alpha)*location_snapshot_weekday_profile.active_evse_ewma[h+1]
                   + p_alpha*excluded.active_evse_ewma[h+1])::real END
          ELSE location_snapshot_weekday_profile.active_evse_ewma[h+1] END
        FROM generate_series(0,23) h),
      valid_count_samples=ARRAY(
        SELECT location_snapshot_weekday_profile.valid_count_samples[h+1]+excluded.valid_count_samples[h+1]
        FROM generate_series(0,23) h),
      excluded_count_samples=ARRAY(
        SELECT location_snapshot_weekday_profile.excluded_count_samples[h+1]+excluded.excluded_count_samples[h+1]
        FROM generate_series(0,23) h),
      count_metric_started_at=coalesce(location_snapshot_weekday_profile.count_metric_started_at,excluded.count_metric_started_at),
      count_metric_last_sample_at=coalesce(excluded.count_metric_last_sample_at,location_snapshot_weekday_profile.count_metric_last_sample_at)
    RETURNING location_id
  ), removed AS (
    DELETE FROM location_snapshot_active_hour a
    USING upsert u WHERE a.location_id=u.location_id AND a.hour_start_utc=p_before_utc
    RETURNING a.location_id
  )
  SELECT count(*) INTO n_location FROM removed;
  RETURN QUERY SELECT n_evse,n_location;
END $func$;


CREATE OR REPLACE FUNCTION snapshot_flush_unchanged_hour(p_expected_sha text)
RETURNS TABLE(evses_written bigint, locations_written bigint, polls_applied integer)
LANGUAGE plpgsql AS $fn$
DECLARE
  pending snapshot_unchanged_hour%ROWTYPE;
  actual_sha text;
  evse_rows bigint;
  location_rows bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(24680,17017);
  SELECT * INTO pending FROM snapshot_unchanged_hour
    WHERE source_name='ndw-locations' FOR UPDATE;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 0::bigint,0::bigint,0::integer;
    RETURN;
  END IF;
  SELECT source_sha256 INTO actual_sha FROM ndw_snapshot_checkpoint
    WHERE source_name='ndw-locations';
  IF actual_sha IS DISTINCT FROM pending.snapshot_sha256
     OR p_expected_sha IS DISTINCT FROM pending.snapshot_sha256 THEN
    RAISE EXCEPTION 'unchanged-hour snapshot does not match committed catalog';
  END IF;
  IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour
            WHERE hour_start_utc<>pending.hour_start_utc)
     OR EXISTS(SELECT 1 FROM location_snapshot_active_hour
            WHERE hour_start_utc<>pending.hour_start_utc) THEN
    RAISE EXCEPTION 'finalize previous active hour before flushing unchanged observations';
  END IF;

  INSERT INTO evse_snapshot_active_hour
   (evse_id,hour_start_utc,category_counts,changed_count,unchanged_count)
  SELECT id,pending.hour_start_utc,
    ARRAY(SELECT CASE WHEN n=(CASE upper(coalesce(e.status,''))
         WHEN 'AVAILABLE' THEN 1 WHEN 'CHARGING' THEN 2
         WHEN 'BLOCKED' THEN 3 WHEN 'RESERVED' THEN 4
         WHEN 'INOPERATIVE' THEN 5 WHEN 'OUTOFORDER' THEN 6
         WHEN 'PLANNED' THEN 8 WHEN 'REMOVED' THEN 9 ELSE 7 END)
         THEN pending.poll_count ELSE 0 END
         FROM generate_series(1,9) n ORDER BY n),
    0,pending.poll_count
  FROM evse e
  ON CONFLICT(evse_id) DO UPDATE SET
    category_counts=ARRAY(
      SELECT evse_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
      FROM generate_series(1,9) n ORDER BY n),
    unchanged_count=evse_snapshot_active_hour.unchanged_count+excluded.unchanged_count
  WHERE evse_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
  GET DIAGNOSTICS evse_rows=ROW_COUNT;

  WITH categories AS (
    SELECT l.id,
      count(e.id) FILTER(WHERE upper(e.status)='AVAILABLE') AS available,
      count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(coalesce(e.status,'')) NOT IN ('PLANNED','REMOVED')) AS active,
      count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(coalesce(e.status,'')) NOT IN
        ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')) AS unknown,
      count(e.id) FILTER(WHERE upper(e.status) IN ('INOPERATIVE','OUTOFORDER')) AS defects
    FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id
    GROUP BY l.id
  )
  INSERT INTO location_snapshot_active_hour
   (location_id,hour_start_utc,category_counts,changed_count,unchanged_count,free_evse_sum,active_evse_sum,valid_count_samples,excluded_count_samples)
  SELECT id,pending.hour_start_utc,
    ARRAY(SELECT CASE WHEN n=(
       CASE WHEN available>0 THEN 1 WHEN active=0 THEN 5
         WHEN unknown>0 THEN 3 WHEN defects=active THEN 4 ELSE 2 END)
       THEN pending.poll_count ELSE 0 END
       FROM generate_series(1,5) n ORDER BY n),
    0,pending.poll_count,CASE WHEN unknown=0 AND active>0 THEN available*pending.poll_count ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN active*pending.poll_count ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN pending.poll_count ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN 0 ELSE pending.poll_count END
  FROM categories
  ON CONFLICT(location_id) DO UPDATE SET
    category_counts=ARRAY(
      SELECT location_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
      FROM generate_series(1,5) n ORDER BY n),
    unchanged_count=location_snapshot_active_hour.unchanged_count+excluded.unchanged_count,
 free_evse_sum=location_snapshot_active_hour.free_evse_sum+excluded.free_evse_sum,
 active_evse_sum=location_snapshot_active_hour.active_evse_sum+excluded.active_evse_sum,
 valid_count_samples=location_snapshot_active_hour.valid_count_samples+excluded.valid_count_samples,
 excluded_count_samples=location_snapshot_active_hour.excluded_count_samples+excluded.excluded_count_samples
  WHERE location_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
  GET DIAGNOSTICS location_rows=ROW_COUNT;

  -- Guard against silently skipped mismatched-hour conflict rows.
  IF evse_rows<>(SELECT count(*) FROM evse)
     OR location_rows<>(SELECT count(*) FROM charging_location) THEN
    RAISE EXCEPTION 'incomplete 304 flush: expected one write per catalog asset';
  END IF;

  DELETE FROM snapshot_unchanged_hour
  WHERE source_name='ndw-locations'
    AND hour_start_utc=pending.hour_start_utc
    AND snapshot_sha256=pending.snapshot_sha256;
  RETURN QUERY SELECT evse_rows,location_rows,pending.poll_count;
END $fn$;


CREATE OR REPLACE FUNCTION snapshot_record_changed_hour(p_observed_at timestamptz)
RETURNS TABLE(evses_written bigint, locations_written bigint)
LANGUAGE plpgsql AS $fn$
DECLARE h timestamptz := date_trunc('hour',p_observed_at);
DECLARE ne bigint;
DECLARE nl bigint;
BEGIN
 PERFORM pg_advisory_xact_lock(24680,17017);
 IF EXISTS(SELECT 1 FROM evse_snapshot_active_hour WHERE hour_start_utc<>h)
 OR EXISTS(SELECT 1 FROM location_snapshot_active_hour WHERE hour_start_utc<>h) THEN
   RAISE EXCEPTION 'previous compact hour must be finalized before changed-feed sample';
 END IF;
 INSERT INTO evse_snapshot_active_hour
 (evse_id,hour_start_utc,category_counts,changed_count,unchanged_count)
 SELECT e.id,h,ARRAY(
  SELECT CASE WHEN n=CASE upper(btrim(coalesce(e.status,'')))
    WHEN 'AVAILABLE' THEN 1 WHEN 'CHARGING' THEN 2 WHEN 'BLOCKED' THEN 3
    WHEN 'RESERVED' THEN 4 WHEN 'INOPERATIVE' THEN 5 WHEN 'OUTOFORDER' THEN 6
    WHEN 'PLANNED' THEN 8 WHEN 'REMOVED' THEN 9 ELSE 7 END
   THEN 1 ELSE 0 END FROM generate_series(1,9) n ORDER BY n),1,0
 FROM evse e
 ON CONFLICT(evse_id) DO UPDATE SET
 category_counts=ARRAY(SELECT evse_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
 FROM generate_series(1,9) n ORDER BY n),
 changed_count=evse_snapshot_active_hour.changed_count+1
 WHERE evse_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
 GET DIAGNOSTICS ne=ROW_COUNT;
 WITH categories AS (
 SELECT l.id,
 count(e.id) FILTER(WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE') available,
 count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')) active,
 count(e.id) FILTER(WHERE e.id IS NOT NULL AND upper(btrim(coalesce(e.status,''))) NOT IN
 ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')) unknown,
 count(e.id) FILTER(WHERE upper(btrim(coalesce(e.status,''))) IN ('INOPERATIVE','OUTOFORDER')) defects
 FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id GROUP BY l.id
 )
 INSERT INTO location_snapshot_active_hour
 (location_id,hour_start_utc,category_counts,changed_count,unchanged_count,free_evse_sum,active_evse_sum,valid_count_samples,excluded_count_samples)
 SELECT id,h,ARRAY(SELECT CASE WHEN n=CASE WHEN available>0 THEN 1 WHEN active=0 THEN 5
 WHEN unknown>0 THEN 3 WHEN defects=active THEN 4 ELSE 2 END THEN 1 ELSE 0 END
 FROM generate_series(1,5) n ORDER BY n),1,0,CASE WHEN unknown=0 AND active>0 THEN available ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN active ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN 1 ELSE 0 END,CASE WHEN unknown=0 AND active>0 THEN 0 ELSE 1 END FROM categories
 ON CONFLICT(location_id) DO UPDATE SET
 category_counts=ARRAY(SELECT location_snapshot_active_hour.category_counts[n]+excluded.category_counts[n]
 FROM generate_series(1,5) n ORDER BY n),
 changed_count=location_snapshot_active_hour.changed_count+1,
 free_evse_sum=location_snapshot_active_hour.free_evse_sum+excluded.free_evse_sum,
 active_evse_sum=location_snapshot_active_hour.active_evse_sum+excluded.active_evse_sum,
 valid_count_samples=location_snapshot_active_hour.valid_count_samples+excluded.valid_count_samples,
 excluded_count_samples=location_snapshot_active_hour.excluded_count_samples+excluded.excluded_count_samples
 WHERE location_snapshot_active_hour.hour_start_utc=excluded.hour_start_utc;
 GET DIAGNOSTICS nl=ROW_COUNT;
 IF ne<>(SELECT count(*) FROM evse) OR nl<>(SELECT count(*) FROM charging_location) THEN
 RAISE EXCEPTION 'changed-feed sample missed assets'; END IF;
 RETURN QUERY SELECT ne,nl;
END $fn$;
