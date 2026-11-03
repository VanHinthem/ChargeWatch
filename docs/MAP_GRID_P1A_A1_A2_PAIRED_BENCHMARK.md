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


## Werkelijke NAS-run A1 — 3 november 2026, 12:39 UTC bericht

**Eerste A1-run uitgevoerd; A2-resultaat van het paar nog niet aangeleverd.** Dit is één meting, geen vergelijkingsconclusie.

| Maatstaf | Uitvoer |
|---|---|
| Execution Time | **770,699 ms** |
| Planning Time | **34,818 ms** |
| Grid-cellen | **8** |
| Geselecteerde locaties | **71.026** |
| Actieve EVSE-rijen tijdens scan | **216.159** (131 gefilterd op inactive) |
| Join-input rijen | **180.949** |
| Spatial index | `ix_charging_location_active_position` (Bitmap Index Scan, 1 index search) |
| Locatiescan | Parallel Bitmap Heap Scan; 2 workers gestart |
| EVSE scan | Seq Scan on evse, active-filter |
| Join / aggregatie | Hash Right Join; interne HashAggregate 71.026 groepen, 1 batch 7193 kB; eind-HashAggregate 8 groepen, 1 batch 289 kB |
| Root buffers | shared hit=1763, read=52252, dirtied=3018, written=10305 |
| JIT | off |

De eerdere grote-regio A1-run was 844,151 ms. De gemeten afname is **niet** causaal te duiden door één run; cache, collector en PostgreSQL-I/O kunnen verschillen. HashAggregates hebben Batches=1 en tonen geen expliciete tijdelijke disk-spill. `dirtied/written` in de buffers is geen bewijs van applicatie-datawijzigingen door een read-only SELECT. Een grote hoeveelheid gedeelde reads/writes kan wel de latency beïnvloeden.

**Vervolg:** voer de A2-query van het originele gepaarde protocol uit en vergelijk totale elapsed time, joinplan, I/O en eventuele spill. Daarna pas omgekeerde herhaling of gerichte optimalisatie. PR #114 draft, geen DB-schema/app/deploy/merge.


## Werkelijke NAS-run A2 — eerste paar compleet, 3 november 2026

Gebruiker heeft A2 uitgevoerd met de grote zoom-8-regio. Vergelijking met direct voorafgaand A1-plan:

| Eigenschap | A1 directe join | A2 EVSE-preaggregatie |
|---|---:|---:|
| Execution Time | 770,699 ms | **736,899 ms** |
| Planning Time | 34,818 ms | 37,944 ms |
| Geselecteerde locaties | 71.026 | 71.026 |
| Gridcellen | 8 | 8 |
| Actieve EVSE-scans | 216.159 rijen (Seq Scan) | 216.159 rijen (Seq Scan) |
| EVSE-resultaat | directe join 180.949 rijen | 84.212 locatiegroepen |
| Root shared hits | 1.763 | 6.381 |
| Root shared reads | 52.252 | 47.598 |
| Root shared writes | 10.305 | 5.739 |
| Temp written | niet gerapporteerd | 349 buffers |
| Materialisatie | locations geheugen | locations geheugen; evse_counts Storage: Disk max 4096 kB |
| Hash aggregates | Batches=1 | Batches=1 |
| Locatiescan | parallel bitmap heap scan, 2 workers | parallel bitmap heap scan, 2 workers |

A2 was **33,800 ms / ~4,4% sneller** in het eerste gepaarde sample. Beide queries hebben dezelfde 71.026 geselecteerde locaties en 8 rastercellen, en lezen dezelfde 216.159 actieve EVSE-rijen. A2-`evse_counts` aggregate groepeert naar 84.212 location IDs; `Storage: Disk` voor de CTE-scan met `temp written=349` is geen `HashAggregate`-batchspill: de aggregates zelf rapporteren Batches=1. Beide uitvoeringen vertonen veel gedeelde reads/writes; cache-/collectorinvloed is niet uitgefilterd. Een *uitvoeringstijdverschil* op zichzelf bewijst evenmin exacte statuscategoriepariteit. De eerste paarmeting rechtvaardigt **nog geen adaptieve queryplanner of indexwijziging**.

**Eerstvolgende P1b:** A2 en A1 in omgekeerde volgorde opnieuw uitvoeren onder vergelijkbare omstandigheden, gevolgd door meerdere afwisselende paren (voorgesteld A1,A2,A2,A1,A1,A2). Noteer `Execution Time` en buffer-/tempgedrag per run. Daarna een kleinschalige Oss-herhaling. Bestaande A1/A2 SQL hergebruiken, niet opnieuw nieuwe varianten of Q5-statusonderzoek starten.
