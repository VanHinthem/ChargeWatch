# ChargeWatch #113 — Q4c-04c synthetische EVSE-deduplicatie

**3 november 2026. Voorbereid, nog niet uitgevoerd.** Q4c-04b vond in productie één kwalificerende locatie (DB-ID 81262) met **2 unieke EVSE's / 2 connectorrijen**. Omdat in deze sample géén dubbele passende connector op één EVSE voorkwam, bewijst die sample niet de cruciale minFree-deduplicatie.

Deze read-only SQL-fixture gebruikt uitsluitend `VALUES`, zonder tijdelijke tabellen, DDL of productiegegevens te veranderen. De fixture bevat bewust meerdere connectorrijen op dezelfde EVSE en onafhankelijk de verwachte minimum-2-beslissing. De productie-`MapFilter` zelf telt EVSE-rijen, niet connectorrijen; deze proef is een controle van de bijbehorende EVSE-count-invariant, geen vervangende productie-filterimplementatie.

## NAS: uitvoeren in Linux-shell

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

WITH fixture(case_name,evse_id,connector_id,status,standard,power_type,watts) AS (
  VALUES
    ('duplicate_only',1,101,'AVAILABLE','ccs2','dc',150000),
    ('duplicate_only',1,102,'AVAILABLE','ccs2','dc',200000),
    ('duplicate_plus_second',1,201,'AVAILABLE','ccs2','dc',150000),
    ('duplicate_plus_second',1,202,'AVAILABLE','ccs2','dc',300000),
    ('duplicate_plus_second',2,203,'AVAILABLE','ccs2','dc',150000),
    ('distinct_two',1,301,'AVAILABLE','ccs2','dc',150000),
    ('distinct_two',2,302,'AVAILABLE','ccs2','dc',150000),
    ('second_unavailable',1,401,'AVAILABLE','ccs2','dc',150000),
    ('second_unavailable',2,402,'CHARGING','ccs2','dc',150000)
),
expected(case_name,connector_rows,unique_evses,min_free_2) AS (
  VALUES
    ('duplicate_only',2::bigint,1::bigint,false),
    ('duplicate_plus_second',3::bigint,2::bigint,true),
    ('distinct_two',2::bigint,2::bigint,true),
    ('second_unavailable',1::bigint,1::bigint,false)
),
actual AS (
  SELECT case_name,
    count(*) FILTER (WHERE status='AVAILABLE' AND standard='ccs2'
                     AND power_type='dc' AND watts>=150000)::bigint AS connector_rows,
    count(DISTINCT evse_id) FILTER (WHERE status='AVAILABLE' AND standard='ccs2'
                     AND power_type='dc' AND watts>=150000)::bigint AS unique_evses
  FROM fixture GROUP BY case_name
),
checks AS (
  SELECT e.case_name,e.connector_rows AS expected_rows,a.connector_rows AS actual_rows,
         e.unique_evses AS expected_evses,a.unique_evses AS actual_evses,
         e.min_free_2 AS expected_pass,(a.unique_evses>=2) AS actual_pass,
         (e.connector_rows=a.connector_rows AND e.unique_evses=a.unique_evses
          AND e.min_free_2=(a.unique_evses>=2)) AS all_ok
  FROM expected e JOIN actual a USING(case_name)
)
SELECT *,bool_and(all_ok) OVER () AS suite_ok
FROM checks ORDER BY case_name;

ROLLBACK;
RESET ROLE;
SQL
```

## Acceptatie

Alle vier `all_ok=t` en `suite_ok=t`. De kernproef `duplicate_only` heeft **twee kwalificerende connectorrijen maar één unieke EVSE**, zodat `minFree=2` **false** moet zijn. De tegenproef `duplicate_plus_second` heeft drie connectorrijen en twee unieke EVSE's, dus **true**. Een niet-beschikbare tweede EVSE mag niet meetellen.

**Beperking:** deze synthetische SQL gebruikt expliciet `count(DISTINCT evse_id)`, niet de C#-querybuilder. Q4a en Q4c-04b leveren het complementaire bewijs voor de productie-dataset. Verdere Q4-05..08 en Q5 blijven open.
