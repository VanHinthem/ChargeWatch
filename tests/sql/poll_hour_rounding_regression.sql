-- Issue #72: demonstratie van afronding per poll-interval.
-- Verwachte PASS: de huidige ::integer-afronding kan >3600 opleveren
-- voor precies 3600 seconden aaneengesloten en niet-overlappende tijd.
-- Geen productietabellen of migraties; alleen SELECT en DO.
DO $check$
DECLARE rounded_sum integer; exact_sum numeric;
BEGIN
 WITH intervals AS (
   SELECT 0.6::numeric AS seconds FROM generate_series(1,4)
   UNION ALL SELECT 3597.6::numeric
 )
 SELECT sum(seconds::integer),sum(seconds)
 INTO rounded_sum,exact_sum FROM intervals;
 IF exact_sum <> 3600 OR rounded_sum <> 3602 THEN
   RAISE EXCEPTION 'Unexpected PostgreSQL numeric-to-integer rounding: exact=% rounded=%',
     exact_sum,rounded_sum;
 END IF;
 RAISE NOTICE 'PASS: 3600 exact seconds become 3602 seconds with per-interval ::integer casts';
END $check$;
