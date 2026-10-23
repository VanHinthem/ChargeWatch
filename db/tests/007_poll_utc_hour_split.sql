-- Deterministic UTC-hour segmentation contract; read-only and rollback-safe.
BEGIN READ ONLY;
DO $$
DECLARE covered integer; first_hour integer; second_hour integer;
BEGIN
 WITH observation AS (
   SELECT '2026-10-23 10:58:00+00'::timestamptz AS start_at,
          '2026-10-23 11:03:00+00'::timestamptz AS end_at
 ), segments AS (
   SELECT h.hour_start_utc,
          extract(epoch FROM least(o.end_at,h.hour_start_utc+interval '1 hour')
                -greatest(o.start_at,h.hour_start_utc))::integer AS seconds
   FROM observation o CROSS JOIN LATERAL generate_series(
      date_trunc('hour',o.start_at),date_trunc('hour',o.end_at),interval '1 hour')
      h(hour_start_utc)
 )
 SELECT sum(seconds)::integer,
        sum(seconds) FILTER (WHERE hour_start_utc='2026-10-23 10:00:00+00'::timestamptz)::integer,
        sum(seconds) FILTER (WHERE hour_start_utc='2026-10-23 11:00:00+00'::timestamptz)::integer
 INTO covered,first_hour,second_hour FROM segments WHERE seconds>0;
 IF covered<>300 OR first_hour<>120 OR second_hour<>180 THEN
   RAISE EXCEPTION 'Wrong UTC hour split: total %, first %, second %',covered,first_hour,second_hour;
 END IF;
END $$;
ROLLBACK;
