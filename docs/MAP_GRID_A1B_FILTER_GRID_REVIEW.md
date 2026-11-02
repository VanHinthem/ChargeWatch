# ChargeWatch #113 — A1b: volledige Web Mercator-cellen + filterpariteit (review)

**Status:** SQL-ontwerp voor beoordeling, **niet uitgevoerd** op PostgreSQL/NAS. Geen productieroute, benchmarkresultaat, migration of deployment. Deze gecontroleerde uitbreiding vervangt de beperkingen van het eerdere [A1-prototype](MAP_GRID_A1_SQL_PROBE.md) voor een regulier Nederlands bbox-gebied (geen antimeridiaan). SQL-parameters zijn in het voorbeeld opgenomen als CTE-constanten; in de echte API parameteriseren met Npgsql en de bestaande `MapFilter`-validator gebruiken.

## Query

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';

-- Veilig voorbeeld: Oss-regio, zoomraster 11 en standaard geen actieve filters.
-- Voor een niet-uitvoerende plancontrole: EXPLAIN (FORMAT JSON) vóór WITH.
-- EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) voert de SELECT WEL uit!
WITH
input AS (
  SELECT 5.40::float8 min_lon, 51.70::float8 min_lat,
         5.62::float8 max_lon, 51.83::float8 max_lat,
         11::int grid_level,
         ARRAY[]::text[] connector_types,
         ARRAY[]::text[] modes,
         NULL::text power_band,
         0::int min_free,
         ARRAY[]::text[] operators
),
validated AS MATERIALIZED (
  SELECT *,
    40075016.68557849::float8 / power(2.0, grid_level) cell_meters
  FROM input
  WHERE grid_level BETWEEN 0 AND 20
    AND min_lon >= -180 AND max_lon <= 180
    AND min_lat >= -85.05112878 AND max_lat <= 85.05112878
    AND min_lon < max_lon AND min_lat < max_lat
    AND min_free IN (0,1,2)
    AND (power_band IS NULL OR power_band IN ('lt50','gte50','gte150','gte300'))
    AND connector_types <@ ARRAY['type2','ccs2','chademo','other']::text[]
    AND modes <@ ARRAY['ac','dc']::text[]
    AND cardinality(operators) <= 100
),
extent AS MATERIALIZED (
  SELECT p.*,
    floor((ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(min_lon,min_lat),4326),3857))
          + 20037508.342789244) / cell_meters)::bigint AS x0,
    floor((ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(max_lon,max_lat),4326),3857))
          + 20037508.342789244) / cell_meters)::bigint AS x1,
    floor((20037508.342789244 - ST_Y(ST_Transform(
          ST_SetSRID(ST_MakePoint(max_lon,max_lat),4326),3857)))
          / cell_meters)::bigint AS y0,
    floor((20037508.342789244 - ST_Y(ST_Transform(
          ST_SetSRID(ST_MakePoint(min_lon,min_lat),4326),3857)))
          / cell_meters)::bigint AS y1
  FROM validated p
),
expanded AS MATERIALIZED (
  SELECT *,
    GREATEST(0::bigint,x0) AS first_x,
    LEAST((power(2.0,grid_level)-1)::bigint,x1) AS last_x,
    GREATEST(0::bigint,y0) AS first_y,
    LEAST((power(2.0,grid_level)-1)::bigint,y1) AS last_y
  FROM extent
),
selection_bounds AS MATERIALIZED (
  SELECT e.*,
    ST_Transform(ST_MakeEnvelope(
      -20037508.342789244 + first_x*cell_meters,
       20037508.342789244 - (last_y+1)*cell_meters,
      -20037508.342789244 + (last_x+1)*cell_meters,
       20037508.342789244 - first_y*cell_meters,3857),4326)::geography AS geom
  FROM expanded e
  WHERE first_x <= last_x AND first_y <= last_y
),
spatial_candidates AS MATERIALIZED (
  SELECT l.id,l.country_code,l.party_id,l.source_id,
         l.latitude,l.longitude,l.operator_name,l.position,
         b.grid_level,b.cell_meters,b.operators,b.connector_types,b.modes,
         b.power_band,b.min_free
  FROM selection_bounds b
  JOIN charging_location l
    ON l.is_active AND l.position IS NOT NULL AND ST_Intersects(l.position,b.geom)
  WHERE cardinality(b.operators)=0
     OR btrim(l.operator_name)=ANY(b.operators)
),
filtered AS MATERIALIZED (
  SELECT s.*,
    floor((ST_X(ST_Transform(s.position::geometry,3857))
          + 20037508.342789244)/s.cell_meters)::bigint cell_x,
    floor((20037508.342789244-
          ST_Y(ST_Transform(s.position::geometry,3857)))
          /s.cell_meters)::bigint cell_y
  FROM spatial_candidates s
  WHERE
    -- Technische EXISTS per locatie zoals MapFilter.LocationPredicate:
    ( (cardinality(s.connector_types)=0 AND cardinality(s.modes)=0 AND s.power_band IS NULL)
      OR EXISTS (
        SELECT 1 FROM evse e
        WHERE e.location_id=s.id AND e.is_active
          AND upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
          AND EXISTS (
            SELECT 1 FROM connector c
            WHERE c.evse_id=e.id AND c.is_active
              AND (cardinality(s.connector_types)=0 OR
                   (CASE upper(coalesce(c.standard,''))
                      WHEN 'IEC_62196_T2' THEN 'type2'
                      WHEN 'IEC_62196_T2_COMBO' THEN 'ccs2'
                      WHEN 'CHADEMO' THEN 'chademo'
                      ELSE 'other' END)=ANY(s.connector_types))
              AND (cardinality(s.modes)=0 OR
                   (CASE WHEN upper(coalesce(c.power_type,'')) IN
                               ('AC_1_PHASE','AC_3_PHASE') THEN 'ac'
                         WHEN upper(coalesce(c.power_type,''))='DC' THEN 'dc'
                         ELSE 'other' END)=ANY(s.modes))
              AND (s.power_band IS NULL OR
                (CASE s.power_band
                  WHEN 'lt50' THEN
                    (CASE WHEN c.max_electric_power>0 THEN c.max_electric_power::bigint
                          WHEN c.max_voltage>0 AND c.max_amperage>0
                          THEN CASE WHEN c.power_type='AC_1_PHASE'
                               THEN round((c.max_voltage::numeric*c.max_amperage))::bigint
                               WHEN c.power_type='AC_3_PHASE'
                               THEN round(((CASE WHEN c.max_voltage<=300 THEN 3.0
                               ELSE sqrt(3.0) END)*c.max_voltage*c.max_amperage)::numeric)::bigint
                               WHEN c.power_type='DC'
                               THEN round((c.max_voltage::numeric*c.max_amperage))::bigint END
                     END) BETWEEN 1 AND 49999
                  ELSE FALSE
                END)
              )
          )
      )
    )
    AND
    ( s.min_free=0 OR (
        SELECT count(*) FROM evse e
        WHERE e.location_id=s.id AND e.is_active
          AND upper(btrim(coalesce(e.status,'')))='AVAILABLE'
          AND ( (cardinality(s.connector_types)=0 AND cardinality(s.modes)=0
                 AND s.power_band IS NULL)
                OR EXISTS (
                 SELECT 1 FROM connector c
                 WHERE c.evse_id=e.id AND c.is_active
                 -- Exact dezelfde connectorvoorwaarden als hierboven moeten
                 -- voor alle powerbands worden hergebruikt; dit prototype
                 -- is voor minFree+technische filters NIET volledig parity-safe.
                 AND (cardinality(s.connector_types)=0 OR
                     (CASE upper(coalesce(c.standard,''))
                       WHEN 'IEC_62196_T2' THEN 'type2'
                       WHEN 'IEC_62196_T2_COMBO' THEN 'ccs2'
                       WHEN 'CHADEMO' THEN 'chademo'
                       ELSE 'other' END)=ANY(s.connector_types))
                 AND (cardinality(s.modes)=0 OR
                     (CASE WHEN upper(coalesce(c.power_type,'')) IN
                       ('AC_1_PHASE','AC_3_PHASE') THEN 'ac'
                       WHEN upper(coalesce(c.power_type,''))='DC' THEN 'dc'
                       ELSE 'other' END)=ANY(s.modes))
               )
          )
      ) >= s.min_free
    )
  )
),
per_location AS (
  SELECT s.id,s.country_code,s.party_id,s.source_id,s.latitude,s.longitude,
         s.cell_x,s.cell_y,
         count(e.id) FILTER (
           WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
         )::bigint total_evses,
         count(e.id) FILTER (
           WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE'
         )::bigint free_evses,
         count(e.id) FILTER (
           WHERE upper(btrim(coalesce(e.status,''))) NOT IN
             ('AVAILABLE','CHARGING','BLOCKED','RESERVED',
              'INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')
         )::bigint unclassified_evses
  FROM filtered s
  LEFT JOIN evse e ON e.location_id=s.id AND e.is_active
  GROUP BY s.id,s.country_code,s.party_id,s.source_id,
           s.latitude,s.longitude,s.cell_x,s.cell_y
),
groups AS (
 SELECT cell_x,cell_y,count(*)::bigint location_count,
    count(*) FILTER (WHERE free_evses>0 AND unclassified_evses=0)::bigint locations_with_free,
    count(*) FILTER (WHERE free_evses=0 AND total_evses>0
                     AND unclassified_evses=0)::bigint locations_with_no_free,
    count(*) FILTER (WHERE total_evses=0 OR unclassified_evses>0)::bigint locations_unknown,
    min(country_code||'-'||party_id||'-'||source_id) singleton_feature_id,
    min(latitude) singleton_latitude,min(longitude) singleton_longitude,
    min(free_evses) singleton_free,min(total_evses) singleton_total
 FROM per_location GROUP BY cell_x,cell_y
)
SELECT cell_x,cell_y,location_count,locations_with_free,
       locations_with_no_free,locations_unknown,
       CASE WHEN location_count=1 THEN singleton_feature_id END feature_id,
       CASE WHEN location_count=1 THEN singleton_latitude END latitude,
       CASE WHEN location_count=1 THEN singleton_longitude END longitude,
       CASE WHEN location_count=1 THEN singleton_free END available_count,
       CASE WHEN location_count=1 THEN singleton_total END total_count
FROM groups ORDER BY cell_y,cell_x;

ROLLBACK;
```

## Reviewgates

**Deze versie toont de volledige-cell-methode, maar is nog NIET gereed om zonder aanpassing te benchmarken met actieve technische filters.** Het vermogensfilter is alleen als `lt50` uitgewerkt, en de `minFree`-subquery herhaalt nog niet alle vermogensvoorwaarden. Dat zou tot afwijkingen van `MapFilter.LocationPredicate` leiden. Voor een correcte filterspecifieke query moet de API **de bestaande server-side predicate-generator hergebruiken** of de logica één keer centraal onderbrengen in een geteste SQL-helper; geen vereenvoudigde losse kopie in productie.

**Gridrand:** de bbox wordt uitgelijnd naar het raster en de spatial query gebruikt de gezamenlijke rechthoek van de volledige cellen; controleer punten exact op gridgrenzen en clamp indices voor coordinaten direct op de wereldrand. Ter voorkoming van doublures bij aangrenzende complete requestsets moet eigenaarschap van de grens semi-open zijn (`min <= x < max`, analoog voor y) en moet de cell-ID eenduidig aan de geprojecteerde puntcoördinaten worden toegekend. De `ST_Intersects`-selectie kan grenspunten bevatten die net buiten het cell-id-bereik vallen; **voor productie** moet de `filtered`-cel expliciet binnen `first_x..last_x` en `first_y..last_y` worden beperkt.

**Performance:** `MATERIALIZED` is hier ter leesbaarheid; kan optimizer-pushdown beperken. Vergelijk alleen gemeten `EXPLAIN`-plannen voor de definitieve variant. Geen run gedaan. Bij query-overbelasting mag Nederland niet incompleet worden geretourneerd; hergroepeer grover of retourneer gecontroleerde fout.

**Semantiek:** onbekende EVSE-statussen zijn conservatief unknown; bronnen/NDW-classifier kunnen meer statussen kennen, dus toets expliciet. Het mid-clustergetal is aantal locaties, segmenten zijn status van locaties (niet EVSE-capaciteit). Bij singleton en onbetrouwbare telling moet Android `?` tonen, dus een vervolgrespons heeft `countReliability` nodig.

**Eerstvolgende actie:** maak alle filtertakken exact pariteit met `MapFilter`, fixeert gridrand semi-open, voeg queryplan- en fixturetests toe. Pas daarna aparte toestemming voor gecontroleerde SQL-executie.
