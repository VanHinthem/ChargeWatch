# ChargeWatch #113 — A2 proef: EVSE's vooraf per locatie aggregeren

**Status 3 november 2026:** gecontroleerde read-only productiemeting voorbereid, **nog niet uitgevoerd**. Vergelijk dezelfde regio, zoom-8-grid en eenvoudige beschikbaarheidsclassificatie als A1 (844,151 ms met JIT uit en parallelisme toegestaan). Het doel is uitsluitend een queryplan- en performancevergelijking, geen definitieve bronkwaliteits- of filterimplementatie.

De eerdere meting omvatte door uitlijning naar hele cellen **70.657 van de 81.004 actieve locaties** en **8 rastercellen**. In het eerdere parallelle plan werd de actieve EVSE-tabel per parallelle deelnemer gescand en extern gesorteerd; de niet-parallelle proef duurde 1.131,140 ms. De cachetoestand varieert en de verschillen zijn niet volledig causaal toe te schrijven.

## A2 read-only meetquery

```sql
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

EXPLAIN (ANALYZE, BUFFERS)
WITH grid AS (
    SELECT
        40075016.68557849 / 256.0 AS cell_size,
        ST_Transform(
            ST_MakeEnvelope(4.15, 51.20, 6.05, 51.85, 4326),
            3857
        ) AS area
),
bounds AS (
    SELECT cell_size,
        floor((ST_XMin(area) + 20037508.342789244) / cell_size)::bigint AS x_min,
        floor((ST_XMax(area) + 20037508.342789244) / cell_size)::bigint AS x_max,
        floor((20037508.342789244 - ST_YMax(area)) / cell_size)::bigint AS y_min,
        floor((20037508.342789244 - ST_YMin(area)) / cell_size)::bigint AS y_max
    FROM grid
),
region AS (
    SELECT cell_size,
        ST_Transform(
            ST_MakeEnvelope(
                -20037508.342789244 + x_min * cell_size,
                 20037508.342789244 - (y_max + 1) * cell_size,
                -20037508.342789244 + (x_max + 1) * cell_size,
                 20037508.342789244 - y_min * cell_size,
                3857
            ),4326
        )::geography geom
    FROM bounds
),
locations AS MATERIALIZED (
    SELECT l.id,
        floor((ST_X(ST_Transform(l.position::geometry,3857))
              + 20037508.342789244)/r.cell_size)::bigint cx,
        floor((20037508.342789244
              -ST_Y(ST_Transform(l.position::geometry,3857)))
              /r.cell_size)::bigint cy
    FROM charging_location l CROSS JOIN region r
    WHERE l.is_active AND l.position IS NOT NULL
      AND ST_Intersects(l.position,r.geom)
),
evse_counts AS MATERIALIZED (
    SELECT e.location_id,
        count(*) FILTER (
            WHERE upper(btrim(e.status)) = 'AVAILABLE'
        ) AS free_count,
        count(*) FILTER (
            WHERE upper(btrim(e.status)) NOT IN ('PLANNED','REMOVED')
        ) AS total_count
    FROM evse e
    WHERE e.is_active
    GROUP BY e.location_id
)
SELECT l.cx,l.cy,
       count(*) AS location_count,
       count(*) FILTER (WHERE coalesce(e.free_count,0)>0) AS with_free,
       count(*) FILTER (
           WHERE coalesce(e.free_count,0)=0 AND coalesce(e.total_count,0)>0
       ) AS without_free,
       count(*) FILTER (WHERE coalesce(e.total_count,0)=0) AS unknown
FROM locations l
LEFT JOIN evse_counts e ON e.location_id=l.id
GROUP BY l.cx,l.cy;

ROLLBACK;
RESET ROLE;
```

Deze proef heeft **dezelfde geografische en eenvoudige statussemantiek** als A1; volledige statusbronkwaliteit en filterpariteit zijn hier bewust nog geen onderwerp. De `MATERIALIZED` CTE is bewust gebruikt om de EVSE-aggregatie **eenmaal vooraf** uit te voeren, niet om op voorhand snelheid te garanderen. Voor kleinere gebieden kan dit juist langzamer zijn doordat de hele actieve EVSE-tabel wordt gegroepeerd.

**Te verifiëren in het resultaat:** exact **70.657 locaties, 8 rastercellen** (als NDW-intussen ongewijzigd is); `HashAggregate` versus `GroupAggregate`, `Hash Join` versus `Merge Join`, `Seq Scan`-aantal op EVSE, `temp read/written`, `shared read/hit`, `Execution Time`, `Planning Time`. De eerste twee vergelijkingen zijn niet gelijkwaardige cold-cache experiments; herhaal indien nodig. Zonder daadwerkelijke SELECT-uitvoering kan PostgreSQL-plannergedrag niet worden bevestigd.

**Geen mutaties:** alleen `EXPLAIN ANALYZE` op SELECT met read-only rol/transactie, statementtimeout van 5 seconden en rollback. Stop bij ongewone belasting of timeouts.
