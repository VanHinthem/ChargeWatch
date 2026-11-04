# ChargeWatch #113 — Q3 horizontale rastergrens en viercellige hoek

**3 november 2026 — read-only SQL-fixture voorbereid, nog niet uitgevoerd.** Q1 (Oss 2.101 locaties, `partition_ok=t`) en Q2 (verticale grens, 3/3 `all_ok=t`) zijn echt op productie geslaagd. Q3 controleert onafhankelijke gevallen die Q2 nog niet bewees.

## Proefopzet

Gebruik zoomniveau 11, wereldvaste grens `x=1055`, `y=681` in Web Mercator. De y-grens ligt bij de **noordrand** van cel `y=681`. Vier aangrenzende cellen worden als aparte kaartverzoeken gesimuleerd: x in `{1054,1055}`, y in `{680,681}`. Punten zijn synthetisch; er wordt niets opgeslagen:
- 1 meter boven, precies op en 1 meter onder de horizontale celgrens, midden in een cel;
- één punt precies op het snijpunt van vier cellen;
- vier punten 1 meter diagonaal van dat hoekpunt.

Bij `ST_Intersects` kan een punt op de grens kandidaat zijn van 2 of 4 cellen. Canoniek floor-/ownership-filter moet het voor de **samengestelde resultaten van de vier aanvragen exact eenmaal** tonen. De numerieke roundtrip via 4326/geography hoort bij de praktijk; verwachte eigenaar van een exact geometrisch grenspunt kan door floatingpoint-roundtrip afwijken, maar mag niet dubbel of ontbrekend zijn.

## Read-only NAS-commando

Kopieer het **hele Bash-blok** inclusief de afsluitende `SQL` op een eigen regel. Bij een interactieve `psql`-sessie alleen de regels tussen `SET ROLE` en `RESET ROLE;` gebruiken.

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
           40075016.68557849::float8/2048.0 AS cell_m,
           1055::bigint AS boundary_x,
           681::bigint AS boundary_y
),
fixtures AS (
    SELECT label,dx_m,dy_m
    FROM (VALUES
        ('north_1m',         100.0::float8,  1.0::float8),
        ('horizontal_exact', 100.0::float8,  0.0::float8),
        ('south_1m',         100.0::float8, -1.0::float8),
        ('corner_exact',       0.0::float8,  0.0::float8),
        ('corner_nw',         -1.0::float8,  1.0::float8),
        ('corner_ne',          1.0::float8,  1.0::float8),
        ('corner_sw',         -1.0::float8, -1.0::float8),
        ('corner_se',          1.0::float8, -1.0::float8)
    ) AS v(label,dx_m,dy_m)
),
points AS (
    SELECT f.label,
           ST_Transform(
               ST_SetSRID(ST_MakePoint(
                 -p.half_world+p.boundary_x*p.cell_m+f.dx_m,
                  p.half_world-p.boundary_y*p.cell_m+f.dy_m
               ),3857),4326
           )::geography AS position,
           p.half_world,p.cell_m
    FROM fixtures f CROSS JOIN p
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
    SELECT p.boundary_x+x.xoff AS req_x,
           p.boundary_y+y.yoff AS req_y,
           ST_Transform(
             ST_MakeEnvelope(
               -p.half_world+(p.boundary_x+x.xoff)*p.cell_m,
                p.half_world-(p.boundary_y+y.yoff+1)*p.cell_m,
               -p.half_world+(p.boundary_x+x.xoff+1)*p.cell_m,
                p.half_world-(p.boundary_y+y.yoff)*p.cell_m,
               3857
             ),4326
           )::geography AS search_area
    FROM p
    CROSS JOIN (VALUES (-1::bigint),(0::bigint)) x(xoff)
    CROSS JOIN (VALUES (-1::bigint),(0::bigint)) y(yoff)
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
FROM per_point ORDER BY label;

ROLLBACK;
RESET ROLE;
SQL
```

## Verwachte controle

**8 regels, allemaal `owned_matches=1`, `exactly_once=t`, `all_ok=t`.** Het veld `candidate_matches` kan groter dan 1 zijn bij de `horizontal_exact` en `corner_exact` fixtures. Controleer ook dat alle `own_x/own_y` binnen `1054..1055` en `680..681` liggen. Als een punt null-eigenaar of `owned_matches=0` toont, is de geometrische grens-/roundtripassumptie niet veilig en moeten we het ontwerp aanpassen (bijvoorbeeld per request bboxes iets ruimer preselecteren, canonieke cell-ID blijft leidend).

Deze gecontroleerde fixture test niet op zichzelf filterpariteit of de betrouwbaarheid van groen/rood/grijs. Die zijn aparte vervolgstappen. Geen schemawijziging, API-/Android-implementatie, merge of deploy.
