# ChargeWatch #113 — implementatie fase A: contracts en Web Mercator raster

**Datum:** 4 november 2026. **Status:** code gecommit op `feat/map-overview-113-phase-a`, **build/test nog door Windows/.NET SDK of GitHub CI te bevestigen**. Alleen deze ontwerp- en pure-codefase; geen nieuw endpoint, SQL, Android, DB, merge of deploy.

## Aangebrachte wijzigingen

- `src/ChargeWatch.Api/MapGrid.cs`: pure wereldvaste Web Mercator-celberekening met `floor()` voor celownership, rasterlevel 0–22, bounds, deterministisch middenpunt, geografische `Bbox` naar volledige celrange, optionele hele-celbuffer (0–8) en controles tegen NaN/Infinity, buitenlimieten en antimeridiaan-crossing. Canonieke constantes overeenkomstig Q1–Q3/P2i–P2l (`WorldHalfMeters=20037508.342789244`, `WorldMeters=40075016.68557849`). `MapGrid.InitialMarkerBudget=500` is voorlopig ontwerp, nog geen runtime enforcement.
- `MapOverviewContracts.cs`: aparte `cluster` en `location` met expliciete System.Text.Json `kind` discriminators. De individuele `featureId` blijft de **bestaande samengestelde OCPI-string**, géén numeriek DB-ID. Cluster bevat `locationCount` en de drie disjuncte availability-segmenten; `HasValidPartition` valideert 3-way-som. Response-contract bevat `requestedBbox`, `requestedZoom`, effectieve `gridLevel`, expliciete complete celdekking, nullable `dataVersion`, bestaande bronversheidsvelden en getypeerde markers.
- `MapOverviewPhaseASelfTest.cs`: checks voor Oss/cel-eigendom, middenpunt, buurcel, begrensd raster, pan-stabiliteit, optionele buffer, wereldgrenzen, ongeldige inputs, statuspartitie, marker-JSON-discriminator en string-`featureId`.
- `Program.cs`: bestaande `--self-test` triggert aanvullend `MapOverviewPhaseASelfTest.Run()`. Geen wijziging aan HTTP-routes.

## Wat deze PR nadrukkelijk niet doet

- Geen `GET /api/v1/map/overview`, SQL-connector-/EVSE-selectie, 500-marker-budgetkeuze, kaartclustering, cache of concurrency; deze vallen onder fasen B/C/D.
- Geen nieuwe beschikbaarheids-/NDW-statusclassifier, filterimplementatie, wijziging aan `/api/v1/map/viewport` of DB-index/migratie.
- `dataVersion` is bewust `string?` en **niet fictief gebaseerd op feedpoll of huidige tijd**. Dit is nog een echte vervolgcontractvraag.
- De zelftests controleren de pure API-types/representatie; ze **bewijzen nog geen volledige server-/Android-integratie of exacte landelijke cells-at-bbox-resultaten onder PostGIS**.
- Het voorlopige maximum van 500 markers is een waarde voor de komende budgetimplementatie, **nog niet afdwingend**.

## Windows-validatie

```powershell
cd C:\GITHUB\ChargeWatch
git fetch origin
git switch feat/map-overview-113-phase-a
git pull --ff-only
dotnet build .\src\ChargeWatch.Api\ChargeWatch.Api.csproj --nologo
if ($LASTEXITCODE -ne 0) { throw 'API build mislukt; niet mergen' }
dotnet run --no-build --project .\src\ChargeWatch.Api\ChargeWatch.Api.csproj -- --self-test
if ($LASTEXITCODE -ne 0) { throw 'API --self-test mislukt; niet mergen' }
```

Controleer dat de uitvoer **`Map overview phase A selftest passed.`** bevat. Bekijk de complete `dotnet build`-uitvoer en waarschuw bij waarschuwingen. De assistant-container heeft geen `dotnet` SDK en heeft daarom deze code **niet lokaal kunnen compileren**.

## Reviewgate fase A

1. Geen verandering aan oude `/map/viewport`-response en locatie-/favoriet-ID's.
2. Canonieke celgrenzen en bufferreeks bevestigd door lokale tests en Q1–Q3-conventie.
3. Json `kind=cluster/location` en featureId als string; niet ongemerkt `featureId` of locatie-detail-ID verwisselen.
4. Relevante testresultaten groen; eventuele regressies oplossen vóór merge.
5. Pas daarna fase B (read-only SQL + endpoint) starten op afzonderlijke branch, bij voorkeur zonder fase-A-review te overslaan.

**Merge, deploy en wijziging aan PR #114 uitsluitend na expliciet akkoord.**


## Gebruikersvalidatie — 4 november 2026

De gebruiker heeft op Windows de volgende uitvoer teruggekoppeld:

```text
ChargeWatch API selftest passed.
Map overview phase A selftest passed.
```

**Resultaat: fase A `--self-test` PASS**, inclusief de bestaande API-selftests en de nieuw toegevoegde raster-/contractcontroles. De concrete console-uitvoer van de afzonderlijke `dotnet build` (inclusief eventuele warnings) is **niet** meegestuurd; daarom is de buildstatus als afzonderlijke verificatie niet formeel bevestigd. Dit is geen bevestiging van werkelijke API-, PostGIS-, Android-, concurrency- of performance-integration tests.

**Volgende stap:** review van draft PR #115 en vervolgens — na akkoord — fase B op een afzonderlijke implementatiebranch. Geen merge of NAS-deployment uitgevoerd.
