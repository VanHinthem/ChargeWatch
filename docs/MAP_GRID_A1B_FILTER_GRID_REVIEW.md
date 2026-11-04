# ChargeWatch #113 — A1b: volledige Web Mercator-cellen + filterpariteit (review)

**Status:** SQL-ontwerp voor beoordeling, **niet uitgevoerd** op PostgreSQL/NAS. Geen productieroute, benchmarkresultaat, migration of deployment. Deze gecontroleerde uitbreiding vervangt de beperkingen van het eerdere [A1-prototype](MAP_GRID_A1_SQL_PROBE.md) voor een regulier Nederlands bbox-gebied (geen antimeridiaan). SQL-parameters zijn in het voorbeeld opgenomen als CTE-constanten; in de echte API parameteriseren met Npgsql en de bestaande `MapFilter`-validator gebruiken.

## Query

> **Review-update (2 november 2026):** de oorspronkelijke query bevatte een onvolledige handmatige kopie van connector-/vermogens-/minFree-filters. Deze is **vervangen door een expliciet C#-SQL-template** waarin de bestaande `MapFilter.LocationPredicate(6)` letterlijk wordt ingevoegd en `AddParameters` dezelfde parameterindexering afhandelt. Deze template is niet rechtstreeks in psql uitvoerbaar en is nog niet tegen PostgreSQL gecompileerd of getest. De voorbeeldpipeline bevat géén volledige bronkwaliteits-/API-integratie.


```sql
-- QUERY TEMPLATE, not executable SQL: {MapFilter.LocationPredicate(...)}
-- must be rendered by the existing parameterized C# MapFilter builder.
-- All other parameters (bbox, grid_level) also use bound parameters.
WITH p AS (
 SELECT $1::float8 min_lon, $2::float8 min_lat,
        $3::float8 max_lon, $4::float8 max_lat,
        $5::integer grid_level,
        40075016.68557849::float8 / power(2.0,$5::integer) cell_m
),
corners AS (
 SELECT p.*,
   ST_Transform(ST_SetSRID(ST_MakePoint(min_lon,max_lat),4326),3857) nw,
   ST_Transform(ST_SetSRID(ST_MakePoint(max_lon,min_lat),4326),3857) se
 FROM p
),
cells AS (
 SELECT *,
   greatest(0::bigint,floor((ST_X(nw)+20037508.342789244)/cell_m)::bigint) first_x,
   least((power(2.0,grid_level)-1)::bigint,
         floor((ST_X(se)+20037508.342789244)/cell_m)::bigint) last_x,
   greatest(0::bigint,floor((20037508.342789244-ST_Y(nw))/cell_m)::bigint) first_y,
   least((power(2.0,grid_level)-1)::bigint,
         floor((20037508.342789244-ST_Y(se))/cell_m)::bigint) last_y
 FROM corners
),
full_cell_bounds AS (
 SELECT c.*,
   ST_Transform(ST_MakeEnvelope(
      -20037508.342789244 + first_x*cell_m,
       20037508.342789244 - (last_y+1)*cell_m,
      -20037508.342789244 + (last_x+1)*cell_m,
       20037508.342789244 - first_y*cell_m,
      3857),4326)::geography search_area
 FROM cells c
),
matched AS (
 SELECT l.id,l.country_code,l.party_id,l.source_id,
        l.latitude,l.longitude,
        greatest(0::bigint,least((power(2.0,b.grid_level)-1)::bigint,
          floor((ST_X(ST_Transform(l.position::geometry,3857))
                 +20037508.342789244)/b.cell_m)::bigint)) cell_x,
        greatest(0::bigint,least((power(2.0,b.grid_level)-1)::bigint,
          floor((20037508.342789244
                  -ST_Y(ST_Transform(l.position::geometry,3857)))/b.cell_m)::bigint)) cell_y
 FROM charging_location l CROSS JOIN full_cell_bounds b
 WHERE l.is_active AND l.position IS NOT NULL
   AND ST_Intersects(l.position,b.search_area)
   {MapFilter.LocationPredicate(startParameter: 6)}
),
owned AS (
 SELECT m.* FROM matched m CROSS JOIN full_cell_bounds b
 WHERE m.cell_x BETWEEN b.first_x AND b.last_x
   AND m.cell_y BETWEEN b.first_y AND b.last_y
),
per_location AS (
 SELECT m.id,m.country_code,m.party_id,m.source_id,
        m.latitude,m.longitude,m.cell_x,m.cell_y,
        count(e.id) FILTER (
          WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
        )::bigint total_evses,
        count(e.id) FILTER (
          WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE'
        )::bigint free_evses,
        count(e.id) FILTER (
          WHERE upper(btrim(coalesce(e.status,''))) NOT IN (
            'AVAILABLE','CHARGING','BLOCKED','RESERVED',
            'INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')
        )::bigint unclassified_evses
 FROM owned m LEFT JOIN evse e ON e.location_id=m.id AND e.is_active
 GROUP BY m.id,m.country_code,m.party_id,m.source_id,
          m.latitude,m.longitude,m.cell_x,m.cell_y
),
clusters AS (
 SELECT cell_x,cell_y,
   count(*)::bigint location_count,
   count(*) FILTER (WHERE free_evses>0 AND unclassified_evses=0)::bigint locations_with_free,
   count(*) FILTER (WHERE free_evses=0 AND total_evses>0
                     AND unclassified_evses=0)::bigint locations_with_no_free,
   count(*) FILTER (WHERE total_evses=0 OR unclassified_evses>0)::bigint locations_unknown,
   min(country_code||'-'||party_id||'-'||source_id) singleton_feature_id,
   min(latitude) singleton_latitude,
   min(longitude) singleton_longitude,
   min(free_evses) singleton_free,
   min(total_evses) singleton_total
 FROM per_location GROUP BY cell_x,cell_y
)
SELECT *,
  CASE WHEN location_count=1 THEN singleton_feature_id END feature_id,
  CASE WHEN location_count=1 THEN singleton_latitude END latitude,
  CASE WHEN location_count=1 THEN singleton_longitude END longitude,
  CASE WHEN location_count=1 THEN singleton_free END available_count,
  CASE WHEN location_count=1 THEN singleton_total END total_count
FROM clusters ORDER BY cell_y,cell_x;
```

## Huidige reviewgates en correctheidsfixtures

**Filterpariteit (structureel opgelost in het ontwerp):** niet langer twee concurrerende kopieën van `MapFilter`. De uiteindelijke API gebruikt hetzelfde `LocationPredicate(startParameter: 6)` en `AddParameters` als het bestaande kaartendpoint. Bij een gewone `MapFilter.None` wordt geen extra predicate toegevoegd. `connectorType`, `mode`, alle vier `power`-banden, `minFree` met én zonder techniekfilter en operators blijven zo uit één generator komen. Parameterwaarden komen uitsluitend uit de bestaande validator; een serverquery mag nooit ongevalideerde stringinput interpoleren.

**Gridgrens (ontwerp):** bbox-corners projecteren naar EPSG:3857; cellen bepalen met `floor`, uiterste cellen klemmen naar `0..2^gridLevel-1`; zoekrechthoek expanderen naar de *hele* benodigde cellen; elk locatiepunt krijgt via projectie en `floor` precies één canonieke cell-ID. Een grenspunt dat `ST_Intersects` aan beide rechthoeken zou toevoegen, valt door de `owned`-cell-ID-check slechts in zijn eigen cel. Deze methode moet worden getest op een celgrens, wereldranden, samenliggende punten en numerieke afronding.

**Belangrijke caveat:** dit is uitsluitend een template. De daadwerkelijke C# SQL-builder moet voorkomen dat een in de CTE niet-zichtbare alias of verkeerd getypeerde Npgsql-parameter binnensluipt. SQL-planner kan door de huidige `CROSS JOIN` en projecties een ander plan kiezen; meet pas na code-/syntaxvalidatie. De `latitude/longitude`-waarden zijn alleen beschikbaar als ze consistent met `position` zijn. Geografische randcoördinaten nabij de polen en antimeridiaan vallen buiten de aanvankelijke Nederlandse contractscope.

**Fixturematrix vóór enig NAS-benchmarkverzoek:**

| Fixture | Filter / grens | Verwacht |
|---|---|---|
| F0 | Geen filter; één en meerdere locaties per cel | Eén singleton of complete cluster; totaal gelijk |
| F1 | `connectorType=ccs2` + `mode=dc` | Zelfde featureId-set als bestaand `MapFilter` |
| F2 | `power=lt50/gte50/gte150/gte300` | Alle grensvermogens conform `EffectiveWattsSql` |
| F3 | `minFree=1/2` zonder technische filters | Dezelfde EVSE-level telling en selecties |
| F4 | `minFree=1/2` met `ccs2+dc+gte150` | MinFree telt alleen passende EVSE's, statusring gebruikt locatiebreed saldo |
| F5 | Twee locaties op exact celgrens, plus aangrenzende requests | Geen ontbrekende of dubbele featureId |
| F6 | Onherkende status of nul operationele EVSE's | Conservatief onbekend; nooit automatisch rood |
| F7 | Laadlocaties met meerdere connectoren per EVSE | Eén EVSE slechts éénmaal in beschikbaarheidsratio |
| F8 | Filters die nul locaties selecteren | Complete lege coverage, geen false missing |
| F9 | Reeks pannen binnen zelfde cellen, dan over rand | Stabiele cell-ID en telling, geen verschuivende clusters |

**Nog niet opgelost:** echte representatieve fixture-executie, PostgreSQL-syntaxis-/plannercheck, contract voor betrouwbare `publishedVersion`, centrale statuskwaliteit en veilige tilecache-invalidatie. Ook de Npgsql-parameterbinding moet in C# worden getoetst. **Dit is geen klaar-voor-productie SQL of NAS-meting.**

## Productiebenchmarks: praktische aanpak (3 november 2026)

De gebruiker bevestigt dat hij de enige ChargeWatch-appgebruiker is en staat open voor directe read-only metingen op productie. **Dit betekent toestemming voor gecontroleerde benchmarks, niet voor muterende databaseoperaties, migraties, merge of deployment.** De NDW-collector draait nog steeds en kan door een onbegrensde SELECT worden beïnvloed.

In `deploy/nas/compose.yaml` heeft de database geen gepubliceerde hostpoort. De API verbindt als `chargewatch_read`; `db/tests/018_readonly_api_role_smoke.sql` bevestigt beoogde SELECT-only privileges. Er is in de huidige tools **geen NAS/SSH-verbinding beschikbaar**. Daarom moeten commando's expliciet door de gebruiker worden uitgevoerd; geen testresultaten claimen voor die stap.

**Stap 1: preflight (uitsluitend leesoperaties).** De bestaande NAS Compose-map is `/volume2/docker/chargewatch`. Door eerst `SET ROLE` te gebruiken en daarna `BEGIN ... READ ONLY` worden inspecties uitgevoerd met read-rechten en in een read-only transactie. Het lokale `chargewatch_owner`-account is alleen voor de containerverbinding; de actieve SQL-rol wordt daarna `chargewatch_read`. Stop onmiddellijk als de rolwissel mislukt.

```sh
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';

SELECT current_user, current_database(), current_setting('transaction_read_only') AS read_only;
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('charging_location','evse','connector')
ORDER BY tablename, indexname;
SELECT count(*) AS active_locations FROM charging_location WHERE is_active;
SELECT count(*) AS active_evses FROM evse WHERE is_active;

ROLLBACK;
RESET ROLE;
SQL
```

De twee COUNT-query's kunnen de tabellen lezen maar wijzigen niets; de timeout stopt een te trage uitvoering. Voor een eerste benchmark wordt apart een **uitvoerbare** A1b-query gegenereerd met de bestaande `MapFilter.LocationPredicate` en Npgsql-parameterbinding. **De SQL-template in dit document is nog niet uitvoerbaar door de placeholder** en moet niet rechtstreeks in psql worden geplakt. Eerst `EXPLAIN (FORMAT JSON)` zonder uitvoering, daarna `EXPLAIN (ANALYZE, BUFFERS)` met korte timeout en oplopende gebieden. Het blijft belangrijk SQL-uitvoering en gelijktijdige collectorbelasting te monitoren.
