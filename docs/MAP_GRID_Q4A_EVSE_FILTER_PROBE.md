# ChargeWatch #113 — Q4a read-only SQL-controle op unieke EVSE-filtertellingen

**Datum:** 3 november 2026. **Status:** direct uitvoerbare read-only SQL-proef voorbereid, **nog niet uitgevoerd**.

**Doel:** op echte productiegegevens rond Oss controleren of een onafhankelijke aggregatie van *unieke EVSE's* voor `connectorType=ccs2&mode=dc&minFree=1` of `minFree=2` dezelfde **locatie-ID-set** oplevert als de EXISTS/count-subquerystructuur van de huidige `MapFilter.LocationPredicate`. Dit is een **SQL-semantiekcontrole**, niet een volledige end-to-end C# `LocationPredicate`/Npgsql-parameterbindingstest. Voor power, operator en source-reliability volgen afzonderlijke checks. Geen wijzigingen.

### Uitvoeren op NAS

Kopieer het **volledige** Bash-blok tot en met de afsluitende `SQL`, op een eigen regel. Voer het in de Linux-shell uit (niet binnen een al geopende `psql`-sessie).

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql \
  -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

WITH selected AS MATERIALIZED (
    SELECT l.id
    FROM charging_location l
    WHERE l.is_active
      AND l.position IS NOT NULL
      AND ST_Intersects(
          l.position,
          ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326)::geography
      )
),
reference_counts AS MATERIALIZED (
    SELECT l.id,
           (
             SELECT count(*)
             FROM evse e
             WHERE e.location_id=l.id
               AND e.is_active
               AND upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
               AND upper(btrim(e.status))='AVAILABLE'
               AND EXISTS (
                   SELECT 1 FROM connector c
                   WHERE c.evse_id=e.id AND c.is_active
                     AND (CASE upper(coalesce(c.standard,''))
                           WHEN 'IEC_62196_T2' THEN 'type2'
                           WHEN 'IEC_62196_T2_COMBO' THEN 'ccs2'
                           WHEN 'CHADEMO' THEN 'chademo'
                           ELSE 'other' END) = ANY(ARRAY['ccs2']::text[])
                     AND (CASE
                           WHEN upper(coalesce(c.power_type,'')) IN
                                ('AC_1_PHASE','AC_3_PHASE') THEN 'ac'
                           WHEN upper(coalesce(c.power_type,''))='DC' THEN 'dc'
                           ELSE 'other' END) = ANY(ARRAY['dc']::text[])
               )
           ) AS matching_free
    FROM selected l
),
derived_counts AS MATERIALIZED (
    SELECT l.id,
           count(e.id) FILTER (
             WHERE upper(btrim(e.status))='AVAILABLE'
               AND EXISTS (
                   SELECT 1 FROM connector c
                   WHERE c.evse_id=e.id AND c.is_active
                     AND upper(coalesce(c.standard,''))='IEC_62196_T2_COMBO'
                     AND upper(coalesce(c.power_type,''))='DC'
               )
           ) AS matching_free
    FROM selected l
    LEFT JOIN evse e
      ON e.location_id=l.id
     AND e.is_active
     AND upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
    GROUP BY l.id
),
checks AS (
    SELECT 1 AS min_free,
           (SELECT count(*) FROM reference_counts WHERE matching_free>=1) AS reference_locations,
           (SELECT count(*) FROM derived_counts WHERE matching_free>=1) AS derived_locations,
           (SELECT count(*) FROM (
               (SELECT id FROM reference_counts WHERE matching_free>=1
                EXCEPT
                SELECT id FROM derived_counts WHERE matching_free>=1)
               UNION ALL
               (SELECT id FROM derived_counts WHERE matching_free>=1
                EXCEPT
                SELECT id FROM reference_counts WHERE matching_free>=1)
           ) x) AS differing_ids
    UNION ALL
    SELECT 2,
           (SELECT count(*) FROM reference_counts WHERE matching_free>=2),
           (SELECT count(*) FROM derived_counts WHERE matching_free>=2),
           (SELECT count(*) FROM (
               (SELECT id FROM reference_counts WHERE matching_free>=2
                EXCEPT
                SELECT id FROM derived_counts WHERE matching_free>=2)
               UNION ALL
               (SELECT id FROM derived_counts WHERE matching_free>=2
                EXCEPT
                SELECT id FROM reference_counts WHERE matching_free>=2)
           ) x)
)
SELECT min_free,reference_locations,derived_locations,differing_ids,
       differing_ids=0 AS ids_match
FROM checks ORDER BY min_free;

ROLLBACK;
RESET ROLE;
SQL
```

### Interpretatie

Verwacht twee regels voor `min_free=1` en `min_free=2`, met `differing_ids=0` en `ids_match=t`. Positieve aantallen zijn nuttig maar niet vereist. De referentie gebruikt de relevante SQL-expressies van `MapFilter.LocationPredicate`; de alternatieve vergelijking gebruikt een zelfstandige `COUNT(e.id) FILTER` en vereenvoudigde, equivalente CCS2/DC connectorcodering. Daarmee bewijzen we bij dit scenario dat connectorrijen niet tot dubbeltellingen van EVSE's leiden en dat referentie- en alternatieve *ID-sets* overeenkomen. Niet claimen dat de uiteindelijke nieuwe C# querygenerator al getest is.

De geografische selectie in deze eerste proef is de oorspronkelijke Oss-bbox, niet de naar hele gridcellen uitgebreide Q1-bbox; de absolute aantallen moeten dus niet worden vergeleken met Q1's 2.101. Geen production schema changes.
