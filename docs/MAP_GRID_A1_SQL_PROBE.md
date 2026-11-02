# ChargeWatch #113 — A1 read-only SQL-proefquery (review, niet uitgevoerd)

**Status 2 november 2026:** uitsluitend een handmatig te beoordelen **read-only SELECT-prototype**. Geen uitvoering op NAS of live database, geen schemawijziging, geen productiebenchmark. Deze query is **alleen ongefilterd** en groepeert **alleen punten binnen de opgegeven bbox**, waardoor de randcellen **niet compleet** zijn. De uiteindelijke filtered API-implementatie moet de bestaande `MapFilter.LocationPredicate` server-side hergebruiken.

## Waarom deze versie beperkt is

De huidige `charging_location.position` is `geography(Point,4326)`, index `ix_location_position` (GiST), `evse(location_id,source_uid)` is uniek. De broncode van `GetMapViewport` telt operationele EVSE's zonder PLANNED/REMOVED en doet uitgebreide connector-JSON-aggregatie. Voor de v2-kaart proberen we eerst een **simpele numerieke groepering zonder connector-join of tarieven**.

De query gebruikt vaste Web Mercator-metercoördinaten van punten en de rasterresolutie gebaseerd op een integer zoomniveau. De kaart-API moet later complete cellen/tiles aanvragen en de werkelijke resultaatsdekking expliciet bewijzen; een willekeurige zichtbare bbox en volledig celbegrip zijn **niet** uitwisselbaar. Deze proef gebruikt daarom een *expliciet bbox-sample* en claimt niet dat een national-ready cachecontract gereed is.

## Uitvoering — slechts na afzonderlijk akkoord

De hieronder getoonde transactie is bedoeld om de query **veilig te reviewen**. Voer het niet op NAS uit zonder apart akkoord; op een productiedatabase moet eerst de rol en schema/indexen gecontroleerd worden.

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';

-- CASE A1: klein proefgebied rondom Oss; geen filters.
-- Voor EXPLAIN: zet EXPLAIN (FORMAT JSON) vóór WITH; dat voert de SELECT niet uit.
-- EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) voert deze SELECT wél uit.
WITH
input AS (
    SELECT
        5.40::double precision AS min_lon,
        51.70::double precision AS min_lat,
        5.62::double precision AS max_lon,
        51.83::double precision AS max_lat,
        11::integer AS grid_level
),
params AS (
    SELECT *,
        (40075016.68557849::double precision / power(2.0, grid_level)) AS cell_meters
    FROM input
    WHERE grid_level BETWEEN 0 AND 20
      AND min_lon < max_lon AND min_lat < max_lat
),
selected AS (
    SELECT l.id, l.country_code, l.party_id, l.source_id,
        l.latitude, l.longitude,
        floor((ST_X(ST_Transform(l.position::geometry, 3857))
               + 20037508.342789244) / p.cell_meters)::bigint AS cell_x,
        floor((20037508.342789244
               - ST_Y(ST_Transform(l.position::geometry, 3857)))
               / p.cell_meters)::bigint AS cell_y
    FROM charging_location l
    CROSS JOIN params p
    WHERE l.is_active
      AND l.position IS NOT NULL
      AND ST_Intersects(
          l.position,
          ST_MakeEnvelope(
              p.min_lon, p.min_lat, p.max_lon, p.max_lat, 4326
          )::geography
      )
),
per_location AS (
    SELECT s.*,
        count(e.id) FILTER (
            WHERE upper(btrim(coalesce(e.status, ''))) NOT IN ('REMOVED','PLANNED')
        )::integer AS total_evses,
        count(e.id) FILTER (
            WHERE upper(btrim(coalesce(e.status, ''))) = 'AVAILABLE'
        )::integer AS free_evses,
        count(e.id) FILTER (
            WHERE upper(btrim(coalesce(e.status, ''))) NOT IN
                ('AVAILABLE', 'CHARGING', 'BLOCKED', 'RESERVED',
                 'INOPERATIVE', 'OUTOFORDER', 'PLANNED', 'REMOVED')
        )::integer AS unclassified_evses
    FROM selected s
    LEFT JOIN evse e ON e.location_id = s.id AND e.is_active
    GROUP BY s.id, s.country_code, s.party_id, s.source_id,
        s.latitude, s.longitude, s.cell_x, s.cell_y
),
groups AS (
    SELECT cell_x, cell_y,
        count(*)::integer AS location_count,
        count(*) FILTER (WHERE free_evses > 0 AND unclassified_evses = 0)::integer AS locations_with_free,
        count(*) FILTER (
            WHERE free_evses = 0 AND total_evses > 0
              AND unclassified_evses = 0
        )::integer AS locations_with_no_free,
        count(*) FILTER (
            WHERE total_evses = 0 OR unclassified_evses > 0
        )::integer AS locations_unknown,
        min(country_code || '-' || party_id || '-' || source_id)
            AS singleton_feature_id,
        min(latitude) AS singleton_latitude,
        min(longitude) AS singleton_longitude,
        min(free_evses) AS singleton_free,
        min(total_evses) AS singleton_total
    FROM per_location
    GROUP BY cell_x, cell_y
)
SELECT cell_x, cell_y, location_count,
    locations_with_free, locations_with_no_free, locations_unknown,
    CASE WHEN location_count = 1 THEN singleton_feature_id END AS feature_id,
    CASE WHEN location_count = 1 THEN singleton_latitude END AS latitude,
    CASE WHEN location_count = 1 THEN singleton_longitude END AS longitude,
    CASE WHEN location_count = 1 THEN singleton_free END AS available_count,
    CASE WHEN location_count = 1 THEN singleton_total END AS total_count
FROM groups
ORDER BY cell_y, cell_x;

ROLLBACK;
```

**Belangrijke statusregel:** deze proef behandelt `total_evses = 0` en onbekende EVSE-statussen als onbekende locatie, ook wanneer er daarnaast beschikbare EVSE's zijn. Dit is een conservatieve **onderzoeksclassificatie**, geen reeds vastgesteld productiecontract. De bestaande live-API heeft een andere parserbetrouwbaarheid en kan onvolledige bronstatussen bevatten; vóór productie moet de betrouwbaarheidsregel centraal worden vastgelegd, onder meer voor een operationele EVSE met ongeldige/onbekende status. Een exact aanwezige EVSE-status `CHARGING`, `BLOCKED` of `RESERVED` is wel betrouwbaar **niet vrij**, niet een ongeldige status. De statuswhitelist moet tegen de werkelijk door NDW gebruikte waarden en de bestaande classifier worden getoetst. De proef is géén volledige implementatie van source-quality flags. Voeg hiervoor eerst regressietests toe.

## Waarop de SQL-review moet letten

- **Indexvriendelijke selectie:** `ST_Intersects(position, envelope::geography)` kan GiST benutten; daadwerkelijke planoptimalisatie pas aantonen met `EXPLAIN`.
- **Geen LIMIT op ruwe locaties:** alle gevonden punten worden aan exact één gridcel toegewezen.
- **Dubbeltellingen:** de query joint op EVSE zonder connector-join en groepeert per locatie vóór grid; één actieve EVSE telt maximaal eenmaal.
- **Geen geveinsde landelijke volledigheid:** dit voorbeeld selecteert uitsluitend binnen de proef-bbox, dus **randcellen zijn onvolledig**. Voor een werkelijk volledig-clustercontract moeten complete gridcellen of tiles inclusief randen worden gebruikt, zodat pan langs een viewportgrens geen verschuivende clustertellers geeft.
- **Grid-coördinaten:** eenvoudig globaal Mercator-grid voor Nederlandse punten. Test grenzen, afronding, zoomwisselingen en lokale coördinaatdubbelen. Bij raster/tegel-overgangen kan het werkelijk gepresenteerde `gridLevel` afwijken van het cameraniveau.
- **Geen filters in deze proef:** latere varianten moeten de huidige `MapFilter.LocationPredicate` exact overnemen; simpele statusaggregaties zijn **niet voldoende** voor connector- en vermogensfilters. Maak die uitbreiding alleen met gerichte filterfixtures.
- **Integergrenzen:** landelijke totalen passen momenteel vermoedelijk binnen `integer`, maar check geobserveerde dataset en gebruik `bigint` binnen aggregatie totdat per-cel bounds bewezen zijn.
- **Snapshot:** `REPEATABLE READ READ ONLY` betekent één consistente database-snapshot binnen de transactie. Geen claim van een publieke versie-/cache-ID afleiden uit alleen de feedcheckpoint.

## Resultaat van de inhoudelijke review

- **Gecorrigeerd:** de eerdere tekst suggereerde onterecht complete rastercellen; de SQL selecteert alleen binnen de bbox. Voor een toekomstige API moeten we eerst de bbox naar volledige rastercelgrenzen uitbreiden en pas dan cellen volledig berekenen.
- **Gecorrigeerd:** onbekende/onclassificeerbare operationele EVSE-statussen werden eerder als betrouwbaar rood meegeteld. Dit prototype telt de locatie conservatief als onbekend; de productieclassifier en bekende NDW-statuswaarden moeten dat verder onderbouwen.
- **Nog niet opgelost:** exacte pariteit van technische en minFree-filters; gridpolygon-bounds en clusterposities; latency en indexgebruik. Het voorbeeld toont enkel SQL-structuur, geen complete API.

## Validatie zonder NAS

De eerste review betreft uitsluitend logisch en syntactisch SQL-ontwerp; dit script is **niet tegen PostgreSQL uitgevoerd**. Pas na akkoord willen we op een begrensde proefdataset `EXPLAIN (FORMAT JSON)` vergelijken, dan eventueel `EXPLAIN (ANALYZE, BUFFERS)` met timeouts, gevolgd door filter- en coveragefixtures. Zie het benchmarkprotocol in [LOCATION_DATAFLOW_ARCHITECTURE.md](LOCATION_DATAFLOW_ARCHITECTURE.md).
