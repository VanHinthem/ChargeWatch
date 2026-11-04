# ChargeWatch #113 — A3 read-only geselecteerde EVSE-aggregatie

**3 november 2026 — SQL-benchmarkkandidaat, niet uitgevoerd.** Voor vergelijking: A1 Oss JIT uit 78,890 ms; A2 Oss 369,545 ms. A1 groot zoom-8-raster 844,151 ms; A2 groot zoom-8-raster 743,756 ms. Omdat data/cache/NAS-belasting varieerden, zijn verschillen slechts voorlopig.

A3 kiest eerst actieve locaties in complete geografische rastercellen en telt vervolgens uitsluitend de actieve EVSE's van die geselecteerde locaties, via een `LATERAL`-aggregatie per locatie. Dit vermijdt de A2-probleemscan van **alle** actieve EVSE's voor een kleine viewport. Het kan bij grotere regio's juist vele indexzoekacties kosten.

## Uitvoeren: eerst Oss met JIT uit

Gebruik de SQL in een psql-sessie als in eerdere benchmarks. De query is een **read-only performanceproef** en gebruikt bewust dezelfde eenvoudige availability-classificatie als A1/A2; de definitieve statuskwaliteit en kaartfilters zijn hierin nog niet verwerkt.

```sql
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

EXPLAIN (ANALYZE, BUFFERS)
WITH grid AS (
    SELECT
        40075016.68557849 / 2048.0 AS cell_size,
        ST_Transform(
            ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326),
            3857
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
    WHERE l.is_active AND l.position IS NOT NULL
      AND ST_Intersects(l.position,r.geom)
),
per_location AS (
    SELECT l.id,l.cx,l.cy,
           c.free_count,c.total_count
    FROM locations l
    LEFT JOIN LATERAL (
        SELECT count(*) FILTER (
            WHERE upper(btrim(e.status))='AVAILABLE'
        ) AS free_count,
        count(*) FILTER (
            WHERE upper(btrim(e.status)) NOT IN ('PLANNED','REMOVED')
        ) AS total_count
        FROM evse e
        WHERE e.location_id=l.id AND e.is_active
    ) c ON true
)
SELECT cx,cy,
       count(*) AS location_count,
       count(*) FILTER (WHERE free_count>0) AS with_free,
       count(*) FILTER (
           WHERE free_count=0 AND total_count>0
       ) AS without_free,
       count(*) FILTER (WHERE total_count=0) AS unknown
FROM per_location
GROUP BY cx,cy;

ROLLBACK;
RESET ROLE;
```

## Tweede run op grotere schaal

Als Oss goed werkt, vervang **alleen de `grid`-CTE** door:

```sql
WITH grid AS (
    SELECT
        40075016.68557849 / 256.0 AS cell_size,
        ST_Transform(
            ST_MakeEnvelope(4.15,51.20,6.05,51.85,4326),
            3857
        ) AS area
),
```

En houd de rest van de read-only query gelijk. Verwacht bij vrijwel ongewijzigde NDW-data circa 70.657 actieve locaties en acht gridcellen; controleer dat in het plan.

**Reviewpunten:** `Execution Time`, `Planning Time`, `Index Scan using idx_evse_active_location`, totaal indexzoekacties, `shared hit/read`, `temp`-I/O, `Nested Loop`/parallelisme, rijen/cluster. De `LATERAL` kan de PostgreSQL-planner richting een indexlookup per locatie sturen; dat is niet gegarandeerd sneller. Dit is geen productiecontract met filterpariteit, volledige gridownership of bronkwaliteit.

**Veiligheid:** uitsluitend SELECT in `REPEATABLE READ READ ONLY`, `SET ROLE chargewatch_read`, timeouts en rollback. Geen NAS-deploy, wijziging of merge.
