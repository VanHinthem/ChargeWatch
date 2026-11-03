# ChargeWatch #113 — Q4c-08b synthetische ring-scope fixture

**3 november 2026 — voorbereid, nog niet uitgevoerd.** Q4c-08 productie-read-only bevat twee geselecteerde locaties, maar beide hebben `has_other_evses=f`. Daarom ontbreekt nog een positieve test voor de invariant: **technisch filter selecteert de locatie; de ring telt vervolgens alle operationele EVSE's van die locatie.**

## NAS — read-only proef

Deze proef gebruikt alleen `VALUES` en een read-only transactie, zonder tijdelijke tabellen of productiegegevens aan te passen. Het is een onafhankelijke scope-invarianttest, **niet** de nog niet bestaande API-implementatie en **niet** de Q5-stalenessclassificatie.

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

WITH fixture(case_name,evse_id,filter_match,operational,status) AS (
  VALUES
    ('filtered_busy_other_free',  1,true,true,'CHARGING'),
    ('filtered_busy_other_free',  2,false,true,'AVAILABLE'),
    ('filtered_free_other_busy',  1,true,true,'AVAILABLE'),
    ('filtered_free_other_busy',  2,false,true,'CHARGING'),
    ('filtered_free_plus_two',    1,true,true,'AVAILABLE'),
    ('filtered_free_plus_two',    2,false,true,'AVAILABLE'),
    ('filtered_free_plus_two',    3,false,true,'CHARGING'),
    ('filtered_busy_other_planned',1,true,true,'CHARGING'),
    ('filtered_busy_other_planned',2,false,false,'PLANNED')
),
expected(case_name,matching_operational,all_operational,all_free,ring_status,has_other_evses) AS (
  VALUES
    ('filtered_busy_other_free',1::bigint,2::bigint,1::bigint,'green',true),
    ('filtered_free_other_busy',1::bigint,2::bigint,1::bigint,'green',true),
    ('filtered_free_plus_two',1::bigint,3::bigint,2::bigint,'green',true),
    ('filtered_busy_other_planned',1::bigint,1::bigint,0::bigint,'red',false)
),
actual AS (
  SELECT case_name,
         count(*) FILTER (WHERE operational AND filter_match)::bigint AS matching_operational,
         count(*) FILTER (WHERE operational)::bigint AS all_operational,
         count(*) FILTER (WHERE operational AND status='AVAILABLE')::bigint AS all_free
  FROM fixture
  GROUP BY case_name
),
checks AS (
  SELECT a.case_name,a.matching_operational,a.all_operational,a.all_free,
         CASE WHEN a.all_operational=0 THEN 'unknown'
              WHEN a.all_free>0 THEN 'green' ELSE 'red' END AS ring_status,
         (a.all_operational>a.matching_operational) AS has_other_evses,
         (a.matching_operational=e.matching_operational
          AND a.all_operational=e.all_operational
          AND a.all_free=e.all_free
          AND (CASE WHEN a.all_operational=0 THEN 'unknown'
                    WHEN a.all_free>0 THEN 'green' ELSE 'red' END)=e.ring_status
          AND (a.all_operational>a.matching_operational)=e.has_other_evses) AS all_ok
  FROM actual a JOIN expected e USING(case_name)
)
SELECT *, bool_and(all_ok) OVER () AS suite_ok
FROM checks ORDER BY case_name;

ROLLBACK;
RESET ROLE;
SQL
```

## Acceptatie

Alle vier `all_ok=t` en `suite_ok=t`. De belangrijkste fixture is `filtered_busy_other_free`: de technisch passende EVSE is bezet, maar de *niet-passende* operationele EVSE is vrij; de voorlopig berekende ring over de gehele geselecteerde locatie moet daarom **green** zijn, niet red. `filtered_busy_other_planned` controleert dat een geplande, niet-operationele EVSE niet meetelt.

**Bewijsgrens:** synthetische tests beproeven de beoogde scope-regel, niet de server-rasteraggregatie (nog niet geïmplementeerd). Q5 moet versheid, bronbetrouwbaarheid en onbekende status nog definitief definiëren. Q4c-04c connector-deduplicatie blijft eveneens open. PR #114 draft. Geen mutaties of deployment.
