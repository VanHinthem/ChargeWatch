# ChargeWatch #113 — P1a gecontroleerde A1/A2-hermeting (grote regio)

**3 november 2026 — voorbereid, nog niet uitgevoerd.** Reeds gemeten A1 (~844 ms groot) en A2 (~744 ms groot) zijn slechts eenmalige samples. Doel: queryplannen direct na elkaar vergelijken met **dezelfde volledige rastercellen** en dezelfde eenvoudige classificatie. Geen nieuwe NDW- of filtersemantiek.

## Stapsgewijs uitvoeren
Start met **één gepaarde run** (A1 gevolgd door A2) op grote zoom-8-regio. Gebruik exact dezelfde SQL-selectievoorwaarden in beide CTE-varianten, en `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`. Elke run heeft een eigen read-only `REPEATABLE READ`-transactie en 5 seconden statement-timeout. De live dataset kan tussen de runs wijzigen; controleer aantallen en voer latere herhalingen in afwisselende volgorde uit. **Geen garantie van vergelijkbare cachecondities met één paar.**

### Run A1 — directe geselecteerde locaties → EVSE JOIN

Gebruik op de NAS het bestaande volledige-grid-script uit [A4](MAP_GRID_A4_SELECTED_JOIN_AGG_BENCHMARK.md), met uitsluitend het onderstaande querydeel vervangen: **A4-`evse_counts` vervangen door `per_location` en eindselectie**, maar dit is een andere uitvoeringsstrategie; de bestaande oude A1-prototype-SQL in [A1](MAP_GRID_A1_SQL_PROBE.md) gebruikt onvolledige viewport-randcellen en is **niet** vergelijkbaar met de complete gridceltest.

Voor reproduceerbare uitvoering is hieronder een zelfstandig script opgenomen.

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
  SELECT 40075016.68557849/256.0 AS cell_size,
         ST_Transform(ST_MakeEnvelope(4.15,51.20,6.05,51.85,4326),3857) AS area
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

### Run A2 — nationale EVSE-preaggregatie

Voer direct daarna **het volledige reeds bestaande SQL-script in** [MAP_GRID_A2_PREAGG_BENCHMARK.md](MAP_GRID_A2_PREAGG_BENCHMARK.md) **uit**, inclusief `SET ROLE`, `BEGIN`, `EXPLAIN`, `ROLLBACK` en `RESET ROLE`. Deze scriptversie gebruikt exact dezelfde zoom-8-grid en dezelfde `AVAILABLE`/`PLANNED`/`REMOVED` proefcategorieën.

### Resultaat terugkoppelen

Kopieer per run het onderste deel van het plan met `Execution Time`, `Planning Time`, `Buffers`, `temp read/written`, `HashAggregate`/`GroupAggregate`, eventuele `Sort Method: external merge`/`Disk`, en relevante `Seq Scan`/`Index Scan`. Vermeld of er een timeout was. **Dit eerste paar is een sanity-check**, nog geen betrouwbare prestatievergelijking. Daarna afwisselende herhalingen, en vervolgens dezelfde aanpak met klein Oss-grid.

Deze eenvoudige A1/A2-queryvormen testen performance, **niet** de definitieve beschikbaarheidsclassifier of complete filtersemantiek; de Q4-tests leveren afzonderlijk het filter-ID-bewijs. Geen productiewijziging, migratie, deploy of merge. PR #114 draft.
