# ChargeWatch #113 — Q2 grenspunten en aangrenzende celverzoeken

**Datum 3 november 2026. Status: read-only, nog niet uitgevoerd.** Voortbouwend op Q1 (Oss: 2.101 unieke/owned/ingedeelde locaties, vier cellen, `partition_ok=t`). Q1 bewijst geen gedrag voor een punt precies op de grens tussen twee wereldvaste Web Mercator-cellen.

## Wat testen we?

Drie **synthetische** punten in EPSG:3857 rond dezelfde verticale rastergrens bij `grid_level=11` (één meter links, exact op de grens en één meter rechts). De punten worden naar WGS84/geography geconverteerd; geen inserts. We simuleren twee aangrenzende aanvragen met volledige rastercelpolygonen. Een punt kan door `ST_Intersects` aan beide celpolygonen raken, maar de canonieke `floor`-eigenaar moet ervoor zorgen dat **elk punt precies eenmaal** als marker uit de twee requests komt.

**Resultaat:** 3 regels, `owned_matches=1` en `all_ok=t` voor elk. `candidate_matches` mag voor het exacte grenspunt 2 zijn, omdat juist de ownershipcheck duplicaten wegfiltert. Als `all_ok=f` of SQL fout is, kan het rastercontract nog niet worden vastgezet. Dit test ook numerieke projectieroundtrips, maar bewijst niet dat elk mogelijk grenspunt op elke breedtegraad veilig is.

## Uitvoerbaar commando

Kopieer het **gehele** blok inclusief afsluitende `SQL`.

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

WITH p AS (
    SELECT 20037508.342789244::float8 AS half_world,
           (40075016.68557849::float8 / 2048.0) AS cell_m,
           1055::bigint AS boundary_x,
           681::bigint AS cell_y
),
points AS (
    SELECT v.label,
           ST_Transform(
             ST_SetSRID(
               ST_MakePoint(
                 -p.half_world + p.boundary_x*p.cell_m + v.dx_m,
                  p.half_world - (p.cell_y+0.5)*p.cell_m
               ),3857
             ),4326
           )::geography AS position,
           p.half_world,p.cell_m
    FROM p CROSS JOIN (
       VALUES ('left_1m',-1.0::float8),
              ('exact_boundary',0.0::float8),
              ('right_1m',1.0::float8)
    ) AS v(label,dx_m)
),
owned_points AS (
    SELECT label,position,
           floor((ST_X(ST_Transform(position::geometry,3857))
                  +half_world)/cell_m)::bigint AS own_x,
           floor((half_world-ST_Y(ST_Transform(position::geometry,3857)))
                  /cell_m)::bigint AS own_y
    FROM points
),
requests AS (
    SELECT (p.boundary_x + v.dx)::bigint AS req_x,
           p.cell_y AS req_y,
           ST_Transform(
             ST_MakeEnvelope(
               -p.half_world+(p.boundary_x+v.dx)*p.cell_m,
                p.half_world-(p.cell_y+1)*p.cell_m,
               -p.half_world+(p.boundary_x+v.dx+1)*p.cell_m,
                p.half_world-p.cell_y*p.cell_m,
               3857
             ),4326
           )::geography AS search_area
    FROM p CROSS JOIN (VALUES (-1::bigint),(0::bigint)) AS v(dx)
),
per_point AS (
    SELECT o.label,o.own_x,o.own_y,
           count(r.req_x) AS candidate_matches,
           count(r.req_x) FILTER (
               WHERE r.req_x=o.own_x AND r.req_y=o.own_y
           ) AS owned_matches
    FROM owned_points o
    LEFT JOIN requests r
      ON ST_Intersects(o.position,r.search_area)
    GROUP BY o.label,o.own_x,o.own_y
)
SELECT label,own_x,own_y,candidate_matches,owned_matches,
       (owned_matches=1) AS exactly_once,
       bool_and(owned_matches=1) OVER () AS all_ok
FROM per_point
ORDER BY label;

ROLLBACK;
RESET ROLE;
SQL
```

## Nog te controleren

Deze proef raakt uitsluitend de verticale grens van twee naast elkaar gelegen cellen op een Nederlandse breedtegraad. Apart nodig: horizontale grens, viercellige hoek, direct aangrenzende of overlappende viewports, filterpariteit met `MapFilter.LocationPredicate`, bronbetrouwbaarheid van vrij/totaal en nationale coverage. De Q1/Q2-rastereigenschap kan vervolgens geautomatiseerd getest worden. Geen mutaties, geen nieuwe SQL-functies, geen database-/Android-/API-wijzigingen, merge of deploy.
