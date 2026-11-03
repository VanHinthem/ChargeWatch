# ChargeWatch #113 — Q1 controle op rastereigenaarschap en classificatie

**3 november 2026. Status: uitvoerbare read-only NAS-proef; nog niet uitgevoerd.** Voert geen mutaties uit en gebruikt geen placeholder. Dit is bewust een **functionele validatie** van het grid- en locatiepartitiecontract, geen nieuwe benchmarkvariant. We beginnen rond Oss met **zoom-11**, daarna kan exact dezelfde controle op de grotere zoom-8-dekking.

## Controlepunten

1. Iedere actieve locatie in het **naar volledige rastercellen uitgevouwen** gebied heeft precies één canonieke `(grid_level,cell_x,cell_y)`-eigenaar.
2. `matched_locations = owned_locations = sum(cell.location_count)` en `with_free + without_free + unknown = owned_locations`.
3. De clusterstatus is de classificatie van **laadlocaties**, niet van losse EVSE's of connectoren.
4. De huidige testclassifier gebruikt alleen eenvoudige EVSE-statusset: `AVAILABLE` groen wanneer er geen onbekende EVSE-status is; rood wanneer er minstens één niet-uitgesloten EVSE, geen AVAILABLE en geen onbekende status is; anders grijs. Dit is **conservatieve prototypeclassificatie, niet definitieve bronbetrouwbaarheid**.
5. Een dubbel raakpunt op een cellgrens wordt gefilterd via de canonieke `cell_x/cell_y`; relatieve bbox-rand verschuift de cellen niet.

## Direct uit te voeren (Oss)

Kopieer **het hele blok**, inclusief de laatste regel `SQL`. De Bash-shell stuurt het complete blok naar `psql`. Alternatief: in interactieve `psql` alleen de regels vanaf `SET ROLE` tot `RESET ROLE` plakken.

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
    SELECT 11::integer AS grid_level,
           40075016.68557849::float8 / 2048.0 AS cell_m,
           ST_Transform(
               ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326),3857
           ) AS area
),
bounds AS (
    SELECT p.*,
           floor((ST_XMin(area)+20037508.342789244)/cell_m)::bigint AS first_x,
           floor((ST_XMax(area)+20037508.342789244)/cell_m)::bigint AS last_x,
           floor((20037508.342789244-ST_YMax(area))/cell_m)::bigint AS first_y,
           floor((20037508.342789244-ST_YMin(area))/cell_m)::bigint AS last_y
    FROM p
),
region AS (
    SELECT b.*,
           ST_Transform(
               ST_MakeEnvelope(
                  -20037508.342789244+first_x*cell_m,
                   20037508.342789244-(last_y+1)*cell_m,
                  -20037508.342789244+(last_x+1)*cell_m,
                   20037508.342789244-first_y*cell_m,
                  3857
               ),4326
           )::geography AS search_area
    FROM bounds b
),
matched AS MATERIALIZED (
    SELECT l.id,
           floor((ST_X(ST_Transform(l.position::geometry,3857))
                  +20037508.342789244)/r.cell_m)::bigint AS cx,
           floor((20037508.342789244
                  -ST_Y(ST_Transform(l.position::geometry,3857)))
                  /r.cell_m)::bigint AS cy
    FROM charging_location l CROSS JOIN region r
    WHERE l.is_active AND l.position IS NOT NULL
      AND ST_Intersects(l.position,r.search_area)
),
owned AS MATERIALIZED (
    SELECT m.*
    FROM matched m CROSS JOIN region r
    WHERE m.cx BETWEEN r.first_x AND r.last_x
      AND m.cy BETWEEN r.first_y AND r.last_y
),
status_counts AS (
    SELECT o.id,o.cx,o.cy,
           count(e.id) FILTER (
              WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE'
           ) AS free_evses,
           count(e.id) FILTER (
              WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
           ) AS total_evses,
           count(e.id) FILTER (
              WHERE upper(btrim(coalesce(e.status,''))) NOT IN (
                  'AVAILABLE','CHARGING','BLOCKED','RESERVED',
                  'INOPERATIVE','OUTOFORDER','PLANNED','REMOVED'
              )
           ) AS unclassified_evses
    FROM owned o
    LEFT JOIN evse e ON e.location_id=o.id AND e.is_active
    GROUP BY o.id,o.cx,o.cy
),
classified AS MATERIALIZED (
    SELECT *,
      CASE
        WHEN unclassified_evses>0 OR total_evses=0 THEN 'unknown'
        WHEN free_evses>0 THEN 'green'
        ELSE 'red'
      END AS category
    FROM status_counts
),
by_cell AS (
    SELECT cx,cy,count(*) AS location_count,
           count(*) FILTER (WHERE category='green') AS green_count,
           count(*) FILTER (WHERE category='red') AS red_count,
           count(*) FILTER (WHERE category='unknown') AS unknown_count
    FROM classified GROUP BY cx,cy
),
totals AS (
    SELECT
      (SELECT count(*) FROM matched) AS matched_locations,
      (SELECT count(DISTINCT id) FROM matched) AS unique_matched_locations,
      (SELECT count(*) FROM owned) AS owned_locations,
      (SELECT count(*) FROM classified) AS classified_locations,
      count(*) AS grid_cells,
      coalesce(sum(location_count),0)::bigint AS cell_location_sum,
      coalesce(sum(green_count),0)::bigint AS green_locations,
      coalesce(sum(red_count),0)::bigint AS red_locations,
      coalesce(sum(unknown_count),0)::bigint AS unknown_locations
    FROM by_cell
)
SELECT *,
       (matched_locations=unique_matched_locations
        AND matched_locations=owned_locations
        AND owned_locations=classified_locations
        AND owned_locations=cell_location_sum
        AND cell_location_sum=green_locations+red_locations+unknown_locations
       ) AS partition_ok
FROM totals;

ROLLBACK;
RESET ROLE;
SQL
```

**Verwacht voor stabiele data:** rond Oss circa 2.100 locaties in 4 cellen; de noodzakelijke eigenschap is `partition_ok = t`. Exacte locatietelling mag door nieuwe NDW-import verschillen. Als de query een fout geeft of `partition_ok=f`, is het gridontwerp nog niet correct — geen implementatiebesluit nemen.

## Vervolg en afbakening

Bij groen resultaat een tweede, onafhankelijke **boundary fixture** met twee hypothetische punten op of vlak naast een gridgrens en opeenvolgende viewport-bboxen; die gebruikt `VALUES` zonder tabelwijzigingen. Pas daarna dezelfde echte dekking onder **MapFilter**-semantiek valideren. Voor filterpariteit moet de productie-API de bestaande `MapFilter.LocationPredicate(6)` en `AddParameters` hergebruiken, niet de filterlogica kopiëren naar een SQL-prototype. Definitieve groen/rood/grijs-classificatie moet ook bronkwaliteit en betrouwbaarheid vergelijken met bestaande Android- en API-contracten. `partition_ok=t` bewijst alleen de testinvarianten voor dit gebied; niet de algemene geldigheid.

**Geen db-mutaties, geen code-/API-/Android-wijzigingen, geen merge/deployment.**
