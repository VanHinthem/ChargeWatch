-- Run only on an isolated migrated test database:
-- psql -v ON_ERROR_STOP=1 -1 -f db/tests/003_statistics_smoke.sql
-- Tests execute inside a transaction and roll back their fixture rows.
BEGIN;

DO $$
DECLARE n integer; actual bigint; expected bigint;
BEGIN
  SELECT count(*) INTO n FROM schema_migration WHERE version='003_statistics';
  IF n<>1 THEN RAISE EXCEPTION 'Migration 003_statistics missing'; END IF;
  SELECT count(*) INTO n FROM information_schema.tables
  WHERE table_schema='public' AND table_name IN
  ('evse_observation_checkpoint','location_observation_checkpoint',
   'evse_occupancy_profile','location_occupancy_profile','evse_daily_reliability',
   'evse_duration_summary','location_zero_available_summary');
  IF n<>7 THEN RAISE EXCEPTION 'Expected seven statistics tables, got %',n; END IF;

  -- CEST -> CET: 2026-10-25 has 25 hours; two occurrences of 02:30
  -- must have distinct instants yet the same Dutch calendar day.
  SELECT extract(epoch FROM (
    ('2026-10-26 00:00'::timestamp AT TIME ZONE 'Europe/Amsterdam')
    - ('2026-10-25 00:00'::timestamp AT TIME ZONE 'Europe/Amsterdam')
  ))::bigint INTO actual;
  IF actual<>90000 THEN RAISE EXCEPTION 'Winter DST day is % sec',actual; END IF;

  -- CET -> CEST: 2026-03-29 has 23 hours.
  SELECT extract(epoch FROM (
    ('2026-03-30 00:00'::timestamp AT TIME ZONE 'Europe/Amsterdam')
    - ('2026-03-29 00:00'::timestamp AT TIME ZONE 'Europe/Amsterdam')
  ))::bigint INTO actual;
  IF actual<>82800 THEN RAISE EXCEPTION 'Summer DST day is % sec',actual; END IF;

  -- Exercise the actual local-midnight splitting formula, not only elapsed time.
  WITH bounds AS (
    SELECT ('2026-10-24 23:55'::timestamp AT TIME ZONE 'Europe/Amsterdam') AS begins_at,
           ('2026-10-25 00:05'::timestamp AT TIME ZONE 'Europe/Amsterdam') AS ends_at
  ), segments AS (
    SELECT d.local_day::date AS local_day,
           extract(epoch FROM least(b.ends_at,
             ((d.local_day + interval '1 day') AT TIME ZONE 'Europe/Amsterdam'))
             - greatest(b.begins_at,
               (d.local_day AT TIME ZONE 'Europe/Amsterdam')))::bigint AS seconds
    FROM bounds b CROSS JOIN LATERAL generate_series(
       date_trunc('day',b.begins_at AT TIME ZONE 'Europe/Amsterdam'),
       date_trunc('day',b.ends_at AT TIME ZONE 'Europe/Amsterdam'),
       interval '1 day') AS d(local_day)
  )
  SELECT sum(seconds), count(*) INTO actual,n FROM segments;
  IF actual<>600 OR n<>2 THEN
    RAISE EXCEPTION 'Expected two 300-second Dutch day segments, got % seconds and % segments',actual,n;
  END IF;

  -- DST winter repetition: ten real minutes within the repeated 02:xx hour
  -- are still ten minutes, never negative or 70 minutes.
  SELECT extract(epoch FROM (
    '2026-10-25T01:35:00Z'::timestamptz -
    '2026-10-25T01:25:00Z'::timestamptz))::bigint INTO actual;
  IF actual<>600 THEN RAISE EXCEPTION 'DST repeated hour elapsed seconds incorrect'; END IF;
END $$;

-- Fixture names are highly unlikely to conflict. Any error aborts without commits.
INSERT INTO charging_location(country_code,party_id,source_id,address,city)
VALUES('ZZ','CW','sprint3-smoke','Smoke','Test')
ON CONFLICT DO NOTHING;
INSERT INTO evse(location_id,source_uid,status)
SELECT id,'sprint3-smoke','AVAILABLE' FROM charging_location
WHERE country_code='ZZ' AND party_id='CW' AND source_id='sprint3-smoke'
ON CONFLICT DO NOTHING;

INSERT INTO evse_observation_checkpoint(evse_id,status_class,source_updated_at,observed_at)
SELECT e.id,'available','2026-10-22T12:00:00Z',now()
FROM evse e JOIN charging_location l ON l.id=e.location_id
WHERE l.country_code='ZZ' AND l.party_id='CW' AND l.source_id='sprint3-smoke'
ON CONFLICT DO NOTHING;
INSERT INTO evse_daily_reliability(evse_id,observation_date,operating_seconds)
SELECT e.id,'2026-10-22',300
FROM evse e JOIN charging_location l ON l.id=e.location_id
WHERE l.country_code='ZZ' AND l.party_id='CW' AND l.source_id='sprint3-smoke'
ON CONFLICT(evse_id,observation_date) DO NOTHING;

DO $$
DECLARE n integer;
BEGIN
  SELECT count(*) INTO n FROM evse_daily_reliability r
  JOIN evse e ON e.id=r.evse_id JOIN charging_location l ON l.id=e.location_id
  WHERE l.country_code='ZZ' AND l.party_id='CW' AND l.source_id='sprint3-smoke'
    AND r.observation_date='2026-10-22' AND r.operating_seconds=300;
  IF n<>1 THEN RAISE EXCEPTION 'Reliability fixture/replay check failed'; END IF;
END $$;

ROLLBACK;
