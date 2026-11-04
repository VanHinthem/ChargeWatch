# ChargeWatch #113 — A4 hybride join-then-aggregate benchmark

**Datum:** 3 november 2026. **Status:** uitsluitend read-only SQL-proef voorbereid; nog niet op PostgreSQL uitgevoerd. Geen DB-schemawijziging, code-implementatie, merge of deployment.

## Doel

A1 telt EVSE's na een gewone join (Oss 78,890 ms, grote zoom-8-cellen 844,151 ms). A2 telt alle actieve EVSE's eerst nationaal (Oss 369,545 ms, grote regio 743,756 ms). A3 telt EVSE's per geselecteerde locatie met LATERAL (Oss 104,811 ms, grote regio 1100,014 ms). De metingen zijn losse samples onder wisselende cache-/collectorbelasting.

A4 **selecteert eerst de actieve locaties**, en doet daarna een **gewone join van die selectie naar actieve EVSE's met GROUP BY location_id**; dit vermijdt zowel de expliciete nationale preaggregatie van A2 als de per-locatie LATERAL-aggregaten van A3. PostgreSQL mag zélf kiezen of het joinplan een hash/merge/nested-loop wordt. Een andere SQL-structuur is geen prestatiegarantie.

## Eerst grote regio, zoom 8 — exact zelfde volledige rastercellen

In de terminal op de NAS, als compleet script via heredoc:

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

EXPLAIN (ANALYZE, BUFFERS)
WITH grid AS (
    SELECT 40075016.68557849 / 256.0 AS cell_size,
           ST_Transform(
               ST_MakeEnvelope(4.15,51.20,6.05,51.85,4326),3857
           ) AS area
),
bounds AS (
    SELECT cell_size,
           floor((ST_XMin(area)+20037508.342789244)/cell_size)::bigint AS x_min,
           floor((ST_XMax(area)+20037508.342789244)/cell_size)::bigint AS x_max,
           floor((20037508.342789244-ST_YMax(area))/cell_size)::bigint AS y_min,
           floor((20037508.342789244-ST_YMin(area))/cell_size)::bigint AS y_max
    FROM grid
),
region AS (
    SELECT cell_size,
           ST_Transform(
               ST_MakeEnvelope(
                   -20037508.342789244+x_min*cell_size,
                    20037508.342789244-(y_max+1)*cell_size,
                   -20037508.342789244+(x_max+1)*cell_size,
                    20037508.342789244-y_min*cell_size,
                   3857
               ),4326
           )::geography AS geom
    FROM bounds
),
locations AS MATERIALIZED (
    SELECT l.id,
           floor((ST_X(ST_Transform(l.position::geometry,3857))
                  +20037508.342789244)/r.cell_size)::bigint AS cx,
           floor((20037508.342789244
                  -ST_Y(ST_Transform(l.position::geometry,3857)))
                  /r.cell_size)::bigint AS cy
    FROM charging_location l CROSS JOIN region r
    WHERE l.is_active
      AND l.position IS NOT NULL
      AND ST_Intersects(l.position,r.geom)
),
evse_counts AS MATERIALIZED (
    SELECT e.location_id,
           count(*) FILTER (
               WHERE upper(btrim(e.status))='AVAILABLE'
           ) AS free_count,
           count(*) FILTER (
               WHERE upper(btrim(e.status)) NOT IN ('PLANNED','REMOVED')
           ) AS total_count
    FROM locations l
    JOIN evse e ON e.location_id=l.id AND e.is_active
    GROUP BY e.location_id
)
SELECT l.cx,l.cy,
       count(*) AS location_count,
       count(*) FILTER (WHERE coalesce(c.free_count,0)>0) AS with_free,
       count(*) FILTER (
           WHERE coalesce(c.free_count,0)=0
             AND coalesce(c.total_count,0)>0
       ) AS without_free,
       count(*) FILTER (WHERE coalesce(c.total_count,0)=0) AS unknown
FROM locations l
LEFT JOIN evse_counts c ON c.location_id=l.id
GROUP BY l.cx,l.cy;

ROLLBACK;
RESET ROLE;
SQL
```

Plak **alles inclusief afsluitende `SQL`-regel**, zonder prompttekst, zodat Bash niet blijft wachten. Alternatief: start interactief `sudo docker compose exec db psql -X -v ON_ERROR_STOP=1 -U chargewatch_owner -d chargewatch` (zonder `-T`) en plak **alleen** regels tussen `SET ROLE` en `RESET ROLE;`; daarna `\\q`.

## Daarna Oss

Als zoom-8-plan succesvol is, vervang **alleen** de `grid`-CTE door:

```sql
WITH grid AS (
    SELECT 40075016.68557849 / 2048.0 AS cell_size,
           ST_Transform(
               ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326),3857
           ) AS area
),
```

## Criteria

Controleer binnen dezelfde periode met exact dezelfde census: 8 cellen en circa 70.664 locaties voor grote regio; 4 cellen en 2.100 locaties rond Oss, mits NDW-data stabiel. Kijk naar echte `HashAggregate`/`GroupAggregate`, `Hash Join`/`Merge Join`/`Nested Loop`, aantal `Index Searches`, `Seq Scan` en eventuele `Disk`-spills; noteer `Execution Time`, `Planning Time`, `shared hit/read`, `temp read/written`. In dit stadium nog géén definitieve status-unknown-betrouwbaarheid of toepassing van alle MapFilter-voorwaarden.

`ON_ERROR_STOP=1` maakt mislukte probe expliciet. Read-only rol en transactie plus 5 seconden SQL-timeout. Bij queryfout niet als succesvolle meting registreren.
