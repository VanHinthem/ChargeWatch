# ChargeWatch #113 — P1c A1/A2 op klein Oss-grid (read-only)

**3 november 2026 — voorbereid, nog niet uitgevoerd.** Vervolg op [P1a/P1b](MAP_GRID_P1A_A1_A2_PAIRED_BENCHMARK.md). De grote-zoom-8-hermetingen toonden na omgekeerde volgorde geen stabiele winnaar. Test nu of eerdere Oss-uitkomsten (A1 ~78,890 ms versus A2 ~369,545 ms) reproduceerbaar zijn.

## Randvoorwaarden

Beide query's gebruiken **dezelfde Oss-bbox (5.40, 51.70, 5.62, 51.83)**, hetzelfde volledige Web Mercator-grid **level 11**, dezelfde eenvoudige statusproef (`AVAILABLE`, `PLANNED`, `REMOVED`) en dezelfde read-only transactie/timeout/JIT-instellingen. De SQL-vormen zijn de bestaande complete-cel A1 en A2 uit P1a, alleen gridlevel en bbox zijn aangepast. Er is geen productcode, filter- of datakwaliteitslogica gewijzigd.

**Eerst slechts één paar uitvoeren:** A1 en daarna A2. Stuur van allebei de **complete EXPLAIN-uitvoer**; alleen `Execution Time` is onvoldoende om eventuele bottlenecks te beoordelen. Controleer bij beide uitvoeringen het `locations`-CTE-aantal en de uiteindelijke `rows` van de eindaggregatie. Als de NDW-data tussentijds verandert, verwacht niet exact dezelfde historische aantallen. Daarna zo nodig A2 → A1 herhalen.

### A1 — direct join, Oss

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='5s';
SET LOCAL lock_timeout='1s';
SET LOCAL jit=off;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH grid AS (
  SELECT 40075016.68557849/2048.0 AS cell_size,
         ST_Transform(ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326),3857) AS area
), bounds AS (
  SELECT cell_size,
         floor((ST_XMin(area)+20037508.342789244)/cell_size)::bigint AS x_min,
         floor((ST_XMax(area)+20037508.342789244)/cell_size)::bigint AS x_max,
         floor((20037508.342789244-ST_YMax(area))/cell_size)::bigint AS y_min,
         floor((20037508.342789244-ST_YMin(area))/cell_size)::bigint AS y_max
  FROM grid
), region AS (
  SELECT cell_size,
         ST_Transform(ST_MakeEnvelope(
           -20037508.342789244+x_min*cell_size,
           20037508.342789244-(y_max+1)*cell_size,
           -20037508.342789244+(x_max+1)*cell_size,
           20037508.342789244-y_min*cell_size,3857),4326)::geography AS geom
  FROM bounds
), locations AS MATERIALIZED (
  SELECT l.id,
         floor((ST_X(ST_Transform(l.position::geometry,3857))
                +20037508.342789244)/r.cell_size)::bigint AS cx,
         floor((20037508.342789244
                -ST_Y(ST_Transform(l.position::geometry,3857)))/r.cell_size)::bigint AS cy
  FROM charging_location l CROSS JOIN region r
  WHERE l.is_active AND l.position IS NOT NULL AND ST_Intersects(l.position,r.geom)
), per_location AS (
  SELECT l.id,l.cx,l.cy,
         count(e.id) FILTER (WHERE upper(btrim(e.status))='AVAILABLE') AS free_count,
         count(e.id) FILTER (WHERE upper(btrim(e.status)) NOT IN ('PLANNED','REMOVED')) AS total_count
  FROM locations l
  LEFT JOIN evse e ON e.location_id=l.id AND e.is_active
  GROUP BY l.id,l.cx,l.cy
)
SELECT cx,cy,count(*) AS location_count,
       count(*) FILTER (WHERE free_count>0) AS with_free,
       count(*) FILTER (WHERE free_count=0 AND total_count>0) AS without_free,
       count(*) FILTER (WHERE total_count=0) AS unknown
FROM per_location GROUP BY cx,cy;
ROLLBACK;
RESET ROLE;
SQL
```

### A2 — EVSE-preaggregatie, Oss

Voer het volgende blok op de **NAS Bash-shell** uit (niet rechtstreeks in een interactieve psql-sessie, vanwege de heredoc). Als je al in psql zit: gebruik alleen de SQL tussen `SET ROLE` en `RESET ROLE`.

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH grid AS (
    SELECT
        40075016.68557849 / 2048.0 AS cell_size,
        ST_Transform(
            ST_MakeEnvelope(5.40, 51.70, 5.62, 51.83, 4326),
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
SQL
```

## Te rapporteren

A1 en A2 `Execution Time`, `Planning Time`, `Buffers`, `temp read/written`, `HashAggregate/GroupAggregate` en eventuele disk-spill, `Join` en `Index Scan/Seq Scan`. Vergelijk geplande met werkelijke cardinaliteit. De query gebruikt `EXPLAIN ANALYZE` op een SELECT; transactie is `REPEATABLE READ READ ONLY` onder `chargewatch_read`, met `statement_timeout='5s'` en `jit=off`. Geen schemawijziging, upgrade, deployment of merge.

## Status

Nog niet uitgevoerd. Eerste resultaat beoordelen vóór verdere iteraties. PR #114 blijft draft.


## Werkelijke NAS-resultaten — 3 november 2026

**P1c eerste Oss-paar uitgevoerd:** volledige queryplannen voor beide varianten ontvangen. Identieke 2.101 locatie-rijen in 4 volledige gridcellen; beide query's lezen 216.219 actieve EVSE-rijen (131 inactive gefilterd). Uitkomsten:

| Eigenschap | A1 directe EVSE-join | A2 landelijke EVSE-preaggregatie |
|---|---:|---:|
| Execution Time | **121,861 ms** | **496,361 ms** |
| Planning Time | 25,396 ms | 27,933 ms |
| Geselecteerde locaties | 2.101 | 2.101 |
| Rastercellen | 4 | 4 |
| Actieve EVSE-rijen gescand | 216.219 | 216.219 |
| Joinresultaat / preaggregatie | 5.099 joinrijen | 84.241 locatiegroepen |
| EVSE scan | Seq Scan 60,086 ms | Seq Scan 98,955 ms |
| Laatste aggregatie | GroupAggregate, quicksort 195 kB | HashAggregate, Batches 1 |
| Geaggregeerde EVSE-CTE | niet van toepassing | Materialized: Storage Disk max 4.096 kB; temp written 349 buffers |
| Root shared buffers hit/read | 760 / 30.422 | 12.431 / 18.726 |
| Root shared buffers written | niet gemeld | 2.329 |

**A1 ~4,07x sneller** bij de eerste Oss-hermeting, in lijn met eerdere losse meting (A1 ~78,9 ms, A2 ~369,5 ms). A2 doet landelijk group-by werk dat een kleine viewport niet nodig heeft. De opvallende resterende A1-kans: ook A1 doet een Seq Scan over 216.219 actieve EVSE's voor een geografische selectie van 2.101 locaties. Dit wijst op een mogelijke winst via een geselecteerde-locaties→EVSE indexstrategie, maar die moet eerst met bestaande indexen en concrete SQL-plannen worden onderzocht. A3 LATERAL was eerder al gemeten (Oss ~104,8 ms, groot ~1.100 ms): niet zonder bewijs opnieuw dezelfde variant introduceren. Groot-zoom8 A1/A2 had géén stabiele winnaar tussen twee omgekeerde paren.

**Vervolg:** inventariseer bestaande indexes op `evse(location_id)` / `evse(is_active,location_id)` en analyseer het indexpad voordat enige schema-aanpassing wordt voorgesteld. Vergelijk alleen bij een aantoonbaar andere selectieve strategie. Geen mutaties, migration, deployment of merge. PR #114 draft.
