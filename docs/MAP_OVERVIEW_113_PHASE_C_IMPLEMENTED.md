# ChargeWatch #113 — fase C: adaptieve map overview (naar main gemerged)

**Datum:** 4 november 2026. **Status:** [PR #117](https://github.com/VanHinthem/ChargeWatch/pull/117) na volledige CI en expliciet akkoord via squash gemerged naar `main`, commit `9fba66e817f788909fd98210236cea9d930f09e3`. **Geen NAS-deployment; `CW_ENABLE_MAP_OVERVIEW` blijft in productie uit.** Vervolg voor real-data performance: [NAS read-only batch](MAP_OVERVIEW_113_NAS_READONLY_BATCH.md).

## Vastgestelde aanpak (gebruikersakkoord)

- Eén zware read-only PostgreSQL-overviewquery per HTTP-request, geen herhaalde landelijke scans voor rasterkeuze.
- Wereldvaste Web Mercator-gridlevels 0–22; startniveau `min(22,floor(zoom)+2)`. Kies vooraf het fijnste kandidaatniveau met conservatieve viewportafmeting-bovengrens (inclusief mogelijke randcel); controleer daarna het daadwerkelijke hele canonieke celbereik. Hoogstens **500 mogelijke niet-lege celmarkers**.
- Eén uniforme resolutie per antwoord. Eén geladen locatie per cel → individuele marker met de bestaande samengestelde `featureId` en numerieke `availableCount/totalCount` (ook `0/0`). Twee of meer locaties per cel → cluster met `locationCount` en disjuncte `locationsWithFree`, `locationsWithNoFree`, `locationsUnknown`.
- Geen `LIMIT` op ruwe locaties of niet-lege cellen, geen afgekapt `complete=true`.
- Coverage is de uitgelijnde, daadwerkelijk gekozen volledige Web Mercator-celrange. Extra serverguard: de berekende celindices moeten binnen `[minCellX,maxCellX] × [minCellY,maxCellY]` liggen, inclusief geldige randpunten. Bestaande fase-B-onderzoekstools houden de historische `MapOverviewSql.Build(MapFilter)`-vorm; de productie-overview gebruikt `Build(..., constrainToCoverage:true)` met expliciete $6–$9-celparameters en filterparameters vanaf $10.
- Gebruik voor de productievariant een indexeerbare **conservatieve geography bounding-box prefilter (`&&`)** in plaats van exact `ST_Intersects` op een geodetische veelhoek. `ST_Intersects(geography)` volgt grootcirkelbogen en kan in kaart-rasterdekking andere resultaten geven dan de canonieke lat/lon-cellen. De strikte celtoets is bepalend; prestatie-/randgevallen zijn testgates.
- `dataVersion=null` blijft; `Cache-Control: no-store` en geen geclaimde consistentie tussen onafhankelijke requests. `feedIsStale` en `coverage.complete` blijven onafhankelijk.
- Extra afgeleid responseveld: `representedLocationCount` voor debug/telemetrie (geen zelfstandige databasepariteitsgarantie). `schemaVersion` en `filterVersion` blijven 1.
- `bufferCells=0` voorlopig. Geen servercache, wijzigingen aan /map/viewport, schema-/index-/NDW-/Androidwijzigingen.

## Automatische acceptatie

De nieuwe `map-overview-http-integration`-job in `.github/workflows/read-only-api.yml` gebruikt een geïsoleerde PostGIS 18 PostgreSQL-service, echte migraties en `chargewatch_read` API-verbinding. De database-eigenaar maakt uitsluitend CI-fixtures aan.

- 625 grid14-cellen, 1001 extra locaties met gedeelde coördinaten, een dubbele locatie, afzonderlijke 1/2-, 0/0- en bezette locaties en vijf celgrensgevallen. Meer dan 1000 locaties moeten volledig in een response terechtkomen.
- Via echte HTTP JSON: cluster/singleton, alle locatie-ID's per respons-cel, statuskleuren en telvelden, `representedLocationCount`, filtercombinaties (operator/CCS2/DC/power/minFree), lage/hoge zoom, pan, ongeldig request → 400, uitschakeling featureflag → 404.
- Onafhankelijke Python-testoracle bepaalt op basis van echte fixturetabellen de verwachte Web Mercator-cel, EVSE-tellingen en gefilterde membership. Groepering vindt niet plaats door de productiesql opnieuw uit te voeren.
- Extra C# `MapOverviewPhaseCSelfTest` voor conservatieve resolutie, budget, pan en SQL-contract.

## Geverifieerde GitHub Actions — 4 november 2026

**Read-only API PASS** op commit `41eee47a233902d2198f2b0a0577f333b76f1a69`, inclusief de onafhankelijke echte HTTP/Npgsql/PostGIS-integratietest:
- API Release build: **0 compilerwarnings, 0 fouten**; fase-A/B/C-selftests PASS.
- Geïsoleerde PostgreSQL 18 + PostGIS 3.6, API draait uitsluitend als `chargewatch_read`.
- Synthetische selectie bevat **1.635** laadlocaties: 625 gridcellen, 1.001 extra samenvallende locaties, 1 duplicaat, drie gerichte losse locaties en vijf canonical-grid-grenspunten (vier kwadranten + exacte hoek).
- Ongefilterde brede request: **1.635 locaties correct gerepresenteerd in 50 markerobjecten (gridLevel 12)**; ook zoomniveaus 4, 7, 17 en 22 en meerdere pan-/viewport-cases volledig gecontroleerd. Niet als gegarandeerde nationale productieclusteraantallen interpreteren.
- Correcte singleton `1/2`, `0/0`, statussegmenten, gecombineerde filters, `minFree=2` lege dataset, gehele dekking, gridownership, budget ≤500, afgeleide locatiecount en polymorfe JSON.
- Ongeldige parameters → HTTP 400; featureflag uit → route HTTP 404; succesresponse `Cache-Control:no-store`.
- Bestaande read-only databasecontractjob en historische API-compatibiliteitstests zijn groen.

[Read-only API-workflowresultaat](https://github.com/VanHinthem/ChargeWatch/actions/runs/41838544647).

**Andere relevante workflows eveneens PASS:** [NAS Images](https://github.com/VanHinthem/ChargeWatch/actions/runs/41838544540) (incl. integratie-/release-safetychecks; PR bouwt images maar deployt niet naar de NAS) en [Tariff snapshot presence](https://github.com/VanHinthem/ChargeWatch/actions/runs/41838544759).

**Bewijsgrens:** CI met gesimuleerde data toont correctness en API-contract; echte NAS-productieperformance (p50/p95, concurrency) en Android UX blijven expliciete latere acceptatiegates.

## Nog niet bewezen door CI

- Werkelijke landelijke NAS HTTP-latency p50/p95, concurrerende gebruikers, Android-renderprestaties en volledige production data-snapshot parity op alle zoomniveaus.
- De geïsoleerde CI-fixture is een correctness-test, geen representatieve productieperformancebenchmark.
- Nog geen betrouwbaar atomair datasetversiecontract. Onafhankelijke HTTP-responses niet als één snapshot mergen.

## Releasegrenzen

PR #117 is gemerged naar `main`; géén NAS-deploy of productie-activatie zonder afzonderlijk akkoord. Vervolgwerk Android kaart/clusterinteractie, gerichte favoriete ID-calls en fysieke toestelacceptatie blijft buiten fase C.
