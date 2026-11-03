# ChargeWatch #113 — voorstel API-overviewcontract en zoom-/coveragegedrag

**Datum:** 3 november 2026  
**Status:** ontwerpvoorstel ter review; **geen implementatie-/merge-/deployakkoord**. Dit document vervangt geen bestaande API zolang migratie niet expliciet is goedgekeurd.

## 1. Vaste productbesluiten

- Nederland moet volledig uitgezoomd zichtbaar zijn, zonder `Zoom verder in` en zonder verlies van locaties door de huidige 999-grens.
- Bestaande filters (`MapFilter.TryParse`, `LocationPredicate`, `AddParameters`) en huidige betrouwbare locatie-/EVSE-statussemantiek blijven **dezelfde bron van waarheid**. Geen afzonderlijke classificatie voor overview, geen wijziging aan NDW-collector en geen filterversoepeling bij nationaal niveau.
- Detaildata worden pas opgehaald bij een individuele locatie. Overview bevat alleen markerinformatie.
- Wereldvast Web Mercator-raster; iedere volledig geselecteerde cel telt alle gefilterde locaties in die cel. Bbox-beweging verandert een cel niet.
- Een cel met één locatie wordt een individuele marker, met meer locaties een cluster. **Clustercentrum neutraal grijs met het aantal locaties; buitenring procentueel groen/rood/onbekend op basis van *locaties*.** Een individuele marker behoudt de bestaande `vrij/totaal`-semantiek.
- Bestaande `GET /api/v1/map/viewport` met 999-cap blijft gedurende migratie intact. Geen afgeleide “volledige kaart” uit een getrunceerd antwoord.

## 2. Bewijsbasis vóór API-implementatie

| Proef | Wat werkelijk aangetoond is | Wat niet |
|---|---|---|
| Q1–Q3 | Stabiele cel-eigendom, grens-/hoekgevallen | Productie-endpoint |
| Q4 | Bestaande `MapFilter` generator en geteste ID-pariteit | Nieuwe filterlogica vereist |
| P2g | Regio CCS2/DC: 420 gelijke IDs, 12 gelijke cellen/statuskolommen | Alle zooms |
| P2h | Regionaal connector-first duidelijk sneller dan geforceerd geo-first | Een universele plan-/zoomdrempel |
| P2i | Ongefilterde NL-omsluitende bbox grid8: 81.934 locaties geselecteerd, 13 cellen, ~986 ms | Onafhankelijke totalen in die run |
| P2j | Later snapshot: **81.945 unieke locaties**, 13 cellen, 0 celverschillen | Landsgrenspolygoon/lege cellen |
| P2k/P2l | Landelijk CCS2/DC grid8: **1.608 IDs**, **11 cellen**, 0 ID-/statusverschillen; performance ~266 ms | Alle filtercombinaties, concurrency of p95 |

**Architectuurkeuze:** behoud voorlopig één functionele SQL-aggregatievorm met directe productie-`MapFilter`-predicate en laat de PostgreSQL-planner de fysieke joinstrategie kiezen. **Geen geforceerde P2e `LATERAL`-route en geen applicatie-dispatch op zoom** op basis van de huidige metingen. Alleen gerichte planneroptimalisatie bij gemeten bottleneck.

## 3. Concept-endpoint (nog te accorderen)

`GET /api/v1/map/overview?bbox=minLon,minLat,maxLon,maxLat&zoom=<number>&connectorType=...&mode=...&power=...&minFree=...&operator=...`

- `bbox` gebruikt dezelfde lon/lat-volgorde en validatie als het huidige API-contract; viewport kan heel Nederland omvatten.
- `zoom` is de daadwerkelijke MapLibre-camera-zoom (decimaal, begrensd tot ondersteunde bereik). De server kiest een **effectieve integer `gridLevel`**; dit is niet noodzakelijk gelijk aan afgeronde camera-zoom.
- Filters blijven dezelfde bestaande parameters en validatieregels. Het serverraster telt **alle** locaties die na `MapFilter.LocationPredicate` geselecteerd zijn.
- Dit is een **nieuw endpoint**: geen uitbreiding van de oude area-/999-grens. Namen/JSON zijn voorstellen, geen bestaande contractwaarden.

### Voorbeeld JSON-schema (illustratief; veldnamen voorstel)

```json
{
  "schemaVersion": 1,
  "filterVersion": 1,
  "requestedBbox": [3.0, 50.6, 7.4, 53.8],
  "requestedZoom": 6.0,
  "gridLevel": 8,
  "coverage": {
    "complete": true,
    "coordinateSystem": "EPSG:3857",
    "minCellX": 0,
    "minCellY": 0,
    "maxCellX": 0,
    "maxCellY": 0
  },
  "feedLastObservedAt": null,
  "feedIsStale": false,
  "markers": [
    {
      "kind": "cluster",
      "gridX": 132,
      "gridY": 84,
      "latitude": 52.1,
      "longitude": 5.3,
      "locationCount": 100,
      "locationsWithFree": 70,
      "locationsWithNoFree": 15,
      "locationsUnknown": 15
    },
    {
      "kind": "location",
      "gridX": 133,
      "gridY": 84,
      "featureId": 12345,
      "latitude": 52.2,
      "longitude": 5.4,
      "availableCount": 1,
      "totalCount": 2,
      "countQuality": "known"
    }
  ]
}
```

*Alle ID's, cellen en coördinaten hierboven zijn fictieve schema-illustraties, niet gemeten data. `minCellX..maxCellY` = voorbeeldplaceholders; bij echte responses moeten deze de **werkelijke, uitgelijnde, volledige opgevraagde celset** aangeven. De getoonde `feedIsStale=false` is slechts schema-illustratie en geen bronstatusclaim.*

## 4. Canonical coverage, lege cellen en pan

- De server converteert de gevraagde bbox naar integer `[minCellX,maxCellX] × [minCellY,maxCellY]` volgens de wereldvaste `gridLevel`-formules (inclusief grens-/antimeridiaanregels). De **complete cell footprint** wordt met een beperkte cachebuffer uitgebreid; `coverage` beschrijft **die echte uitgebreide set**, niet de oorspronkelijke pixelviewport.
- `coverage.complete=true` betekent: iedere actieve, gefilterde locatie waarvan het centrum in één van die volledige cellen valt, is **precies één keer** in een van de markers verwerkt. Het betekent **niet**: “iedere gridcel heeft een marker”, “de hele wereld is in de huidige response opgevraagd”, of “de feed is vers”.
- Lege cellen hebben **geen marker** en zijn **impliciet** compleet omdat het rechthoekige celbereik plus `complete=true` is gepubliceerd. Geen duizenden lege markerrijen.
- Bij exact op een cellgrens liggende posities geldt dezelfde `floor`- en exclusieve rechtsonderregel als Q1–Q3; een locatie krijgt één canonical cel. Toewijzing en groepering mogen niet per bbox relatief worden berekend.
- Binnen één response vormen de clusters en individuen een **disjuncte partitionering** van de gefilterde locaties. Onderlinge overlap van **twee** responses is toegestaan, maar de client dedupliceert op `(gridLevel, gridX, gridY, filterKey, dataVersion)`.
- `complete=false` wordt **niet** gebruikt als een quasi-succes dat onzichtbare punten negeert; bij overschrijding van budget kiest de server eerst een grovere **complete** resolutie en meldt de effectieve `gridLevel`. Als zelfs dat faalt: expliciete fout / expliciet `coverage.complete=false` met reden, nooit stilzwijgende truncatie.

## 5. Typen, status en consistentie

**Cluster (`locationCount >= 2`):**

- `gridX/gridY` geven de stabiele cel aan; `locationCount` is **aantal laadlocaties**, niet EVSE's of connectoren.
- `locationsWithFree`, `locationsWithNoFree`, `locationsUnknown` zijn gehele waarden uit de **bestaande locatiebrede betrouwbaarheidsteling**, disjunct en samen exact `locationCount`.
- Middenlabel alleen `locationCount`. Buitenring: `360° × segmentCount/locationCount`; groen/rood/grijs. Geen EVSE-`vrij/totaal` in het clustercentrum.
- `latitude/longitude` voor te renderen clusterpin: **deterministisch binnen de cel** (bijvoorbeeld celcentrum). Een markerpositie die meeverschuift bij pannen is niet toegestaan. `cellBounds` is afleidbaar uit gridX/gridY/gridLevel en hoeft niet telkens mee als dezelfde formule wordt gedeeld.

**Individuele locatie (`locationCount == 1`):**

- `featureId` = bestaand stabiel API-locatie-ID. Geo-marker op echte locatiepositie (geen celcentrum).
- Een lichte betrouwbare `availableCount/totalCount/countQuality`-projectie volgens **de bestaande endpoint-classifier**, inclusief mogelijke `null`/`unknown`. Niet een nieuw SQL-COUNT-schema tot domeinwaarheid promoveren.
- Bij aantallen van 0/2 kan de bestaande groene/rode markerregel gelden; `?` alleen als betrouwbaar tellen onmogelijk is. Technische filters bepalen **locatie-inclusie**, niet impliciet een andere teller van alle EVSE's in geselecteerde locatie.
- Detail- en compacte sheet kunnen op `featureId` opnieuw gegevens ophalen. Bestaande `/api/v1/locations/{id}` blijft daarvoor leidend.

**Versheid:** hergebruik bestaande `feedLastObservedAt` / `feedIsStale`-betekenissen; de geldigheid van een cache-item is niet hetzelfde als het laatst werkelijk waargenomen laadstatusmoment. Het antwoord mag onbekende status niet stilzwijgend als rood/geen vrij presenteren.

## 6. Objectbudget en zoomovergang: voorlopig beleid

- Server legt een **configureerbaar maximaal aantal markers** per response vast (de grens is **niet** de 999-ruwe-locatiecap). Voorgestelde **startwaarde 500 markers**, uitsluitend als implementatie-/testhypothese en niet als vastgesteld productgetal.
- Zoek de **fijnste gridLevel** die in de uitgelijnde coverage tot maximaal dat aantal niet-lege cellen leidt. Indien te veel niet-lege cellen: **grover** gridLevel proberen en opnieuw volledig aggregeren; nooit `LIMIT` op ruwe locaties of clusters.
- Vermijd dat een kleine pan de resolutie steeds laat omschakelen: zoom-buckets met hysterese of bestaande tilecache; effectieve `gridLevel` altijd teruggeven. Een camera-pan moet dezelfde cached cellen op dezelfde zoomlaag behouden.
- Op lage zoom vooral grotere clusters; middenzoom mix van individuele markers en clusters; hoge zoom vooral individuele markers. `locationCount==1` is objecttypebeslissing, niet de zoomwaarde alleen.
- Bij selecteren van een cluster: Android zoomt naar **de hele celbounds**, met passende marge voor zoekbalk/resultatenpaneel. Bij exact overlappende locaties op maximale bruikbare zoom: een **begrensde/gepagineerde locatieselectie** op cel/gebied als aparte API-beslissing (geen oneindige zoom).
- Favorieten blijven lokaal in Android; geen favoritenaam/locatie-ID-upload alleen voor clustermarkering. Cluster-favorietster uitsluitend tonen indien lokale favoriet zeker tot de **gefilterde**, geladen clusterpopulatie behoort; anders geen sterclaim.

**Open besluit:** exacte zoom→gridLevel mapping, max 500 markerbudget, hysterese, cachebuffer en maximale zoom zijn pas vast te stellen na payload- en Android-renderproeven. Een harde zoomdrempel voor SQL-planstrategie wordt **niet** voorgesteld.

## 7. Cache-, request- en foutcontract

- Bestaande `/map/viewport` blijft onder zijn huidige 999-/1°²-grenzen werken totdat een geteste Android-client het nieuwe `/map/overview`-contract gebruikt.
- Android zet in-flight requests van eerdere camera/filter/generatie opzij (generation token) en combineert alleen geverifieerde **complete** celresponses met dezelfde `gridLevel`, genormaliseerde filterKey en dataVersion. Overlappende requests mogen dezelfde cellen niet dubbel renderen.
- De server publiceert de gekozen `gridLevel`, werkelijke celrange, **`complete`**, filterversie en een expliciete **dataVersion/snapshot-identificatie** als die betrouwbaar uit de bestaande gegevensbron beschikbaar is. Indien geen betrouwbare atomair gepubliceerde snapshot-id bestaat, is dit een *open API-blocker*; niet kunstmatig baseren op `NOW()` of alleen feedpoll-tijd.
- Cache key (ontwerp): `schemaVersion + gridLevel + normalizedFilters + canonicalCellRange + dataVersion`; bij niet-atomair geldige dataset alleen kortlevende cache of geen cross-request-merging.
- Bij overbelasting/timeout: expliciete `503` of `504` met `error.code`, `retryable`, en **geen gedeeltelijke markers als complete coverage**; bij ongeldige bbox/filter `400`. Over budget bij maximale aggregatie: expliciete validatie-/budgetfout, geen `413 Zoom verder in` als standaardroute van het nieuwe endpoint.
- NDW fallback levert geen bewezen **landelijk equivalent geaggregeerd endpoint**: alleen expliciet beperkte fallback met duidelijke waarschuwing; niet als volledige landelijke overview voordoen.
- Monitoring voor productie: SQL p50/p95, bbox/celrange, geselecteerde locaties, gridLevel, niet-lege markers, responsebytes, EVSE/connectorreads, incomplete/foutresponses, cache hitratio en feedversheid. De P2i–P2l EXPLAIN-cijfers zijn **geen** gegarandeerde API-SLA.

## 8. Acceptatie vóór productiemigratie

1. Exacte volledige celpopulatie zonder limiet 999: `SUM(locationCount) == COUNT(DISTINCT selected.id)`, plus ID-/celtoewijzing, voor Oss, regio en nationale bbox; gebruik bestaande Q1–Q4/P2j/P2l-fundering.
2. Filterlogica **direct** via `MapFilter`, zonder duplicatie. Alleen gerichte regressietests voor echte API-parameter-/data-flow; niet opnieuw alle Q4-onderzoeksmatrix verplicht doorlopen.
3. Betrouwbare statusclassificatie en clustersegmenten vanuit dezelfde productiegegevens; `free + noFree + unknown = locationCount` voor elke cluster.
4. Kamerapan van 1–2 pixels, grens- en hoekpunten, overlappende bufferresponses, snel afwisselen van filters/zooms: stabiele IDs/geen dubbeltellingen.
5. Lage zoom toont heel het Nederlandse omsluitende gebied in een **complete** celset met aantal markers binnen budget; middenzoom mix; hoge zoom losse markers; exact gedeelde posities -> expliciete chooser.
6. No-data/unknown/stale en NDW-fallback niet verwarren met groen/rood of complete nieuwe data.
7. Responsegrootte en werkelijke Android MapLibre render-/frameperformance meten op fysiek toestel; API p50/p95 + gelijktijdige requests op de NAS beoordelen vóór deploy.
8. Oude kaartendpoint tijdens overgang onveranderd houden en fallback/rollbackpad vastleggen.

## 9. Implementatievolgorde zodra expliciet akkoord gegeven wordt

1. **API-contract-/type- en SQL-projectie**: nieuw endpoint, correcte canonical-cell-coverage, geparametriseerde `MapFilter`, locatiebrede bestaande statusprojectie. Unit/integratiechecks en read-only vergelijking.
2. **Responsebudget / cache / conflictbehandeling**: server kiest de fijnste volledige resolutie binnen budget; consistente metadata, generatie-/versieafhandeling.
3. **Android integratie**: nieuwe lightweight markers en vaste clusteridentiteit, kleur/ring, clustertap op cell bounds, details via ID; lokale favorieten blijven.
4. **End-to-end test / staged release**: NAS API-timing, volledige nationale overview, fysieke toestel-UX, terugval naar bestaand endpoint zolang volledige dekking niet bewezen is.

**Expliciete beslispunten nog open:** precieze API-vorm/velden, markerbudget, zoom-buckets, cachestrategie/snapshot-versie, cellen buiten Nederland en het UX-gedrag bij identieke coördinaten. De gebruiker heeft alleen akkoord gegeven om dit **ontwerp** verder uit te werken.
