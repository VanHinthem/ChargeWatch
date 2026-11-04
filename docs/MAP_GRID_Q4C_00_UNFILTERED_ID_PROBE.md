# ChargeWatch #113 — Q4c-00 ongefilterde viewport-versus-grid ID-set

**3 november 2026. Status: voorbereid, nog niet uitgevoerd.** De gebruiker heeft in de kleine bbox `(5.50,51.75,5.53,51.77)` **98 actieve locaties** geteld, ruim onder de huidige viewportlimiet van 999. Q4a (EVSE SQL-semantiektest) en Q4b (14 C# generator/parameterchecks) zijn geslaagd.

## Doel en afbakening

Controleer met één PostgreSQL-snapshot of de **bestaande ongefilterde viewport-ID-set** identiek is aan de **ongefilterde volledige Web Mercator-rasterselectie, beperkt tot exact dezelfde kleine bbox**. Dit test raster-expansie en ownership met echte locatie-ID's, niet alleen aantallen. De volledige rasterselectie bevat naar verwachting méér locaties dan de kleine viewport; daarom is `overview_clipped` de juiste vergelijkingsbasis. De 999-limiet is uitsluitend relevant voor de bestaande endpoint, niet voor het nieuwe grid. Dit is **Q4-00 zonder technische filters**: geen bewijs van Q4-01..08 en nog geen end-to-end Npgsql/PostgreSQL-binding.

## Read-only NAS-commando

Kopieer het complete blok in de NAS-shell (niet in interactieve psql).

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

WITH p AS (
  SELECT 11::integer AS grid_level,
         40075016.68557849::float8 / 2048.0 AS cell_m,
         ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography AS viewport_area,
         ST_Transform(ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326),3857) AS mercator_bbox
),
bounds AS (
  SELECT p.*,
         floor((ST_XMin(mercator_bbox)+20037508.342789244)/cell_m)::bigint AS first_x,
         floor((ST_XMax(mercator_bbox)+20037508.342789244)/cell_m)::bigint AS last_x,
         floor((20037508.342789244-ST_YMax(mercator_bbox))/cell_m)::bigint AS first_y,
         floor((20037508.342789244-ST_YMin(mercator_bbox))/cell_m)::bigint AS last_y
  FROM p
),
region AS (
  SELECT b.*,
         ST_Transform(ST_MakeEnvelope(
           -20037508.342789244+first_x*cell_m,
            20037508.342789244-(last_y+1)*cell_m,
           -20037508.342789244+(last_x+1)*cell_m,
            20037508.342789244-first_y*cell_m,
           3857),4326)::geography AS search_area
  FROM bounds b
),
viewport_ids AS MATERIALIZED (
  SELECT l.id
  FROM charging_location l CROSS JOIN p
  WHERE l.is_active AND l.position IS NOT NULL
    AND ST_Intersects(l.position,p.viewport_area)
),
grid_candidates AS MATERIALIZED (
  SELECT l.id,l.position,
         floor((ST_X(ST_Transform(l.position::geometry,3857))
                +20037508.342789244)/r.cell_m)::bigint AS cell_x,
         floor((20037508.342789244
               -ST_Y(ST_Transform(l.position::geometry,3857)))
               /r.cell_m)::bigint AS cell_y
  FROM charging_location l CROSS JOIN region r
  WHERE l.is_active AND l.position IS NOT NULL
    AND ST_Intersects(l.position,r.search_area)
),
grid_owned AS MATERIALIZED (
  SELECT g.id,g.position
  FROM grid_candidates g CROSS JOIN region r
  WHERE g.cell_x BETWEEN r.first_x AND r.last_x
    AND g.cell_y BETWEEN r.first_y AND r.last_y
),
overview_clipped AS MATERIALIZED (
  SELECT g.id FROM grid_owned g CROSS JOIN p
  WHERE ST_Intersects(g.position,p.viewport_area)
),
diff AS (
  (SELECT id FROM viewport_ids EXCEPT SELECT id FROM overview_clipped)
  UNION ALL
  (SELECT id FROM overview_clipped EXCEPT SELECT id FROM viewport_ids)
)
SELECT
  (SELECT count(*) FROM viewport_ids) AS viewport_ids,
  (SELECT count(*) FROM grid_candidates) AS grid_candidates,
  (SELECT count(*) FROM grid_owned) AS grid_owned,
  (SELECT count(*) FROM overview_clipped) AS overview_clipped_ids,
  (SELECT count(*) FROM diff) AS differing_ids,
  (SELECT count(*) FROM viewport_ids) =
    (SELECT count(DISTINCT id) FROM viewport_ids) AS viewport_unique,
  (SELECT count(*) FROM overview_clipped) =
    (SELECT count(DISTINCT id) FROM overview_clipped) AS overview_unique,
  NOT EXISTS(SELECT 1 FROM diff) AS ids_match;

ROLLBACK;
RESET ROLE;
SQL
```

**Acceptatie:** `viewport_ids=overview_clipped_ids`, `differing_ids=0`, `viewport_unique=t`, `overview_unique=t`, `ids_match=t`. `grid_candidates` en `grid_owned` mogen groter zijn, omdat die alle volledige rastercellen beslaan. Exacte telling kan door voortschrijdende NDW-import variëren, dus 98 is een preflightwaarde en geen hardcoded assert. Stop bij timeout of mismatch.

**Vervolg:** Q4c-01+ wordt met C# `MapFilter.LocationPredicate(5/6)` en `AddParameters` gegenereerd; geen handmatige SQL-kopie van het connector-/power-/operatorpredicaat. Voor het volledig sluitende bewijs moeten beide selecties onder dezelfde databaseverbinding/snapshot worden uitgevoerd. Geen schemawijziging of deployment.


## Werkelijke NAS-uitvoering — 3 november 2026

De gebruiker voerde het bovenstaande read-only PostgreSQL-blok uit en rapporteerde:

```text
viewport_ids | grid_candidates | grid_owned | overview_clipped_ids | differing_ids | viewport_unique | overview_unique | ids_match
------------ +-----------------+------------+----------------------+---------------+-----------------+-----------------+----------
          98 |             419 |        419 |                   98 |             0 | t               | t               | t
(1 row)
```

**Q4c-00 PASS** voor de geteste Oss-bbox: exacte ongefilterde ID-setovereenkomst en beide selecties uniek. De 419 grid-owned locaties vertegenwoordigen volledige cellen, tegenover de 98 locaties in de kleinere viewport; het vergelijken van volledige grid-IDs direct met viewport-IDs zou daarom onjuist zijn. Dit resultaat bewijst nog geen technische filterpariteit, Q5-bronkwaliteit of landelijke prestatie. Geen productie- of schemamutaties, deploy of merge.
