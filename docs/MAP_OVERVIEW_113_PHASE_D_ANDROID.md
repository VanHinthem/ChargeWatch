# ChargeWatch #113 — fase D: Android-landkaart (draft PR #119)

> **Actuele duiding — 5 november 2026:** de hier beschreven fase-D-implementatie uit draft PR #119 is inmiddels in `main` gemerged via [PR #119](https://github.com/VanHinthem/ChargeWatch/pull/119); daarna volgden [PR #120](https://github.com/VanHinthem/ChargeWatch/pull/120), [#121](https://github.com/VanHinthem/ChargeWatch/pull/121) en [#122](https://github.com/VanHinthem/ChargeWatch/pull/122) voor natuurlijke, consistente clustering en favorietsterren. De onderstaande oorspronkelijke melding *niet gemerged* is historisch. **Nog niet bevestigd:** productieactivatie van `CW_ENABLE_MAP_OVERVIEW`, echte NAS HTTP-p50/p95/concurrency en een besluit om de overview standaard te maken. Zie [STATUS](STATUS.md) en [#113](https://github.com/VanHinthem/ChargeWatch/issues/113).


**Datum:** 4 november 2026  
**Status:** geïmplementeerd op `feat/map-overview-113-phase-d-android`, in [draft PR #119](https://github.com/VanHinthem/ChargeWatch/pull/119). **Niet gemerged of gedeployed.** `CW_ENABLE_MAP_OVERVIEW` blijft op de NAS uit.

## Contract & runtime

- `MapOverviewClient` vraagt `GET /api/v1/map/overview?bbox=...&zoom=...` met de bestaande `ChargingFilters.appendTo` en de **onbegrensde zichtbare MapLibre-bbox** (geen oude 1 graad²-bounding).
- De parser vereist `schemaVersion=1`, `filterVersion=1`, `coverage.complete=true`, maximaal 500 markers, één marker per rastercel, 1:1 statuspartitionering van clusters en exacte `representedLocationCount`. Locaties houden een samengestelde string-`featureId` en numerieke `0/0`.
- Het protocol levert momenteel `dataVersion=null`; geen cel- of locatiemerging tussen HTTP-responses. Elke complete response vervangt de vorige kaartdataset atomair. Bij niet-succes blijft de bestaande geldige kaartdata staan met foutmelding; geen onjuiste landelijke legacyfallback.
- `MapViewModel` invalideert HTTP-generaties onmiddellijk bij pan/zoom/filter, cancelt pending jobs en debouncet 400 ms. Overzicht en legacy blijven gescheiden; legacy ondersteunt nog de oorspronkelijke 1 graad²-grens en NDW-fallback binnen die grens.
- `MapScreen` rendert backendclusters zonder tweede client-side clustering en maakt een Canvas-ring op basis van `locationsWithFree / locationsWithNoFree / locationsUnknown`, met een grijs telcentrum. Alleen individuele markers krijgen een favorietster via `featureId`. Oude clustermethode blijft behouden voor legacy.
- Clustertik: fit van canonieke Web Mercator-cel met MapLibre-padding voor overlays; op maximaal bruikbare zoom (18) een beperkte/gepagineerde keuzelijst.
- De tijdelijke moduswissel staat in **Instellingen → Kaartmodus** en wordt lokaal bewaard. Standaard **Bestaande kaart**. Dit is geen serverfeatureflag en kan het uitgeschakelde endpoint niet alsnog activeren.
- Bestaande adreszoekmarker, vijf dichtstbijzijnde laadlocaties, GPS, filters, favorites, navigatie en Street View blijven op hun huidige codepad. De bestaande `LocationEnrichmentClient` hergebruikt de detailpayload om de nieuwe minimale marker in de sheet met adres, operator en connectorcategorieën aan te vullen.

## Nieuw paginated endpoint (feature-gated, alleen lezen)

`GET /api/v1/map/overview/cell?gridLevel={0..22}&gridX={x}&gridY={y}&offset={0..100000}&limit={1..100}`

Standaard: `offset=0`, `limit=50`. Accepteert dezelfde `MapFilter`-queryparameters als overview. Retourneert `gridLevel`, `gridX`, `gridY`, `offset`, `limit`, `totalCount`, `hasMore` en `items` met `featureId`, longitude/latitude, adres, operator, availableCount en totalCount. Cellen worden via dezelfde wereldvaste Web Mercator `floor()`-formule bepaald. Geen ruwe-locatie-truncatie; paginering is expliciet. `Cache-Control: no-store` en `X-ChargeWatch-Filter-Version: 1`.

De SQL draait in een **REPEATABLE READ READ ONLY** transactie onder de bestaande `chargewatch_read`-rol. Een geografische voorselectie beperkt het gebied, de exacte canonical rasterindex beslist lidmaatschap. Er zijn geen writes, nieuwe tabellen, indexen of updates aan oude `/map/viewport`. Omdat er nog geen publiceerbare datasetversie bestaat, is cross-page snapshotconsistentie bij een ondertussen veranderende databron niet gegarandeerd. De UI toont de actuele paginagrootte en kan opnieuw laden.

## Validatie en nog open releasegates

- `android/.github/workflows/android.yml`: unit tests en debug APK-build op de draft PR; nieuwe pure testcases voor coverage, regio, clusterpartition, 0/0, paginering en canonical celbounds.
- `.github/workflows/read-only-api.yml`: bestaande .NET build/selftests en echte geïsoleerde HTTP/PostGIS-overviewtest; uitgebreid met celpaging-oracle op dezelfde 1.635 synthetische locaties en filterpariteit.
- **Nog vereist vóór release:** actuele groene PR-CI; fysieke Galaxy S23 Ultra-test van laadlocatiemarkers, verschillende zooms, tap/overlays, geselecteerde locatie, meerdere gelijke coördinaten en performance; HTTP-p50/p95 en concurrency op de NAS in een expliciet geautoriseerde rollout/testfase.
- Geen productie-NAS-deployment, geen automatische omschakeling van gebruikers en geen squash merge zonder afzonderlijke toestemming.

**Bewijsgrens:** de reeds geslaagde NAS SQL-batch in [fase C](MAP_OVERVIEW_113_NAS_READONLY_BATCH.md) bewijst volledige SQL-celdekking, niet werkelijke Android-FPS of productie-HTTP-p95. Die testmatrix wordt niet opnieuw uitgevoerd.
