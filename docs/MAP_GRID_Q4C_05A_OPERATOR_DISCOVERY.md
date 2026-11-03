# ChargeWatch #113 — Q4c-05a operatorfixture verkennen

**3 november 2026. Status: voorbereid, nog niet uitgevoerd.** Q4c-04b heeft op de kleine Oss-bbox een positieve combinatie van CCS2/DC/gte150/minFree=2 aangetoond. Q4c-04c synthetische multi-connector-deduplicatie is **nog niet als uitgevoerd bevestigd**.

## Waarom deze preflight?

`MapFilter.TryParse` accepteert operatornamen als `text[]`. De werkelijke selectie vergelijkt `btrim(l.operator_name) = ANY($n::text[])` zonder case-folding. Eerst moeten we weten welke werkelijke operatornamen in de kleine Q4c-viewport aanwezig zijn. Anders testen we mogelijk uitsluitend twee lege ID-sets. We gebruiken voor de daadwerkelijke Q4c-05-pariteit daarna nog steeds de centrale C#-`MapFilter`-generator; deze query is alleen read-only gegevensverkenning, geen nieuwe filterimplementatie.

## NAS — run volledig Bash-blok

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

SELECT
  btrim(l.operator_name) AS operator_name,
  count(*)::bigint AS location_count,
  count(*) FILTER (WHERE l.operator_name IS DISTINCT FROM btrim(l.operator_name))::bigint AS source_values_with_edge_whitespace
FROM charging_location l
WHERE l.is_active AND l.position IS NOT NULL
  AND ST_Intersects(
    l.position,
    ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography
  )
  AND l.operator_name IS NOT NULL
  AND btrim(l.operator_name) <> ''
GROUP BY btrim(l.operator_name)
ORDER BY location_count DESC, operator_name
LIMIT 15;

SELECT count(*) AS null_or_blank_operator_locations
FROM charging_location l
WHERE l.is_active AND l.position IS NOT NULL
  AND ST_Intersects(
    l.position,
    ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography
  )
  AND (l.operator_name IS NULL OR btrim(l.operator_name) = '');

ROLLBACK;
RESET ROLE;
SQL
```

## Vervolg

Kies op basis van werkelijke resultaten één frequent voorkomende en één tweede bestaande operator; behoud exact de door de database geretourneerde spelling. Test daarna via de bestaande `MapFilter.TryParse`/`LocationPredicate`/`AddParameters` de ID-setpariteit bij één, twee en herhaalde operatorparameters. Controleer dat herhaling niet verdubbelt en `btrim`- en null-gedrag behouden blijft. Q4c-05a levert uitsluitend bronwaarden op, **geen geslaagde filtertest**.

Alle SELECTs draaien met `chargewatch_read` in één read-only transactie; geen API/Android-code, schemawijzigingen, NAS-deploy of merge. PR #114 blijft draft.


## Werkelijk NAS-resultaat — 3 november 2026

Gebruiker voerde de operator-discoveryquery uit. Exacte tellingen in kleine Oss-bbox: Vattenfall InCharge **87**, 50five **6**, bp pulse **1**, E-Flux by Road **1**, Laadnet **1**, PowerGo **1**, Qwello Benelux BV **1**. Samen **98** locaties, en voor alle zeven operatorwaarden **0** bronwaarden met edge-whitespace. Dit is bewijs van aanwezige operatornamen, **geen bewijs van filterpariteit**. De drie gegenereerde vergelijkingen staan klaar in [MAP_GRID_Q4C_05B_OPERATOR_PARITY_PROBE.md](MAP_GRID_Q4C_05B_OPERATOR_PARITY_PROBE.md).
