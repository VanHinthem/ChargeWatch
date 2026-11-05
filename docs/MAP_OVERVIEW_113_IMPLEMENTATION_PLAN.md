# ChargeWatch #113 — technisch implementatieplan Map Overview API

**Datum:** 3 november 2026  
**Status:** uitvoeringstraject en reviewgates voorbereid; **geen productieimplementatie, merge of deployment geautoriseerd**.  
**Leidend ontwerp:** [MAP_OVERVIEW_API_CONTRACT_113_DESIGN.md](MAP_OVERVIEW_API_CONTRACT_113_DESIGN.md). **Voorlopig overeengekomen markerbudget:** 500 objecten per volledige response; bij Android-metingen herzienbaar.

## 1. Aanleiding en precieze code-audit

De bestaande `Program.cs` exposeert `GET /api/v1/map/viewport` met `MapFilter.TryParse`, `ApiOptions.TryParseBbox`, een **1,0 graad²**-restrictie, en een **413** zodra het `GetMapViewport`-resultaat getrunceerd is bij 1.000 ingelezen locaties. In `ApiDatabase.GetMapViewport` selecteert de SQL `ORDER BY id LIMIT 1000` en `MapFilter.LocationPredicate(5)`. Dat oude contract **ongewijzigd** laten werken tijdens de migratie.

De bestaande `MapLocationItem.FeatureId` is een **string** van `country_code-party_id-source_id`, geen PostgreSQL `charging_location.id`. `GetLocation(long id)` gebruikt daarentegen een intern `long`-ID, en `/api/v1/locations/by-ndw-id/{featureId}` geeft een andere externe lookup. **Belangrijke correctie op het eerdere conceptvoorbeeld:** een fictieve numerieke `featureId:12345` mag **niet** ongemerkt het bestaande kaart-ID vervangen. Ontwerpresultaat: behoud in het nieuwe `location`-kaartobject de bestaande samengestelde `featureId`-string voor Android/favorieten. Als de detailcall een intern ID nodig heeft, lever desgewenst een **apart veld `locationId:long`** na validatie van de bestaande privacy-/linkcontracten; maak geen impliciete conversie `featureId → long`. De definitieve keuze moet door Android-consumenten worden geverifieerd vóór implementatie.

`GetMapViewport` aggregeert nu `count(DISTINCT e.id)` (AVAILABLE / exclusief REMOVED+PLANNED) en bouwt via `jsonb_agg` connectorcategorieën. De nieuwe overview moet **geen zware connector-JSON laden**; maar de P2i–P2l-proef gebruikte enkele `count(e.id)`-expressies met `unclassified`-criterium. Die proefclassificatie is nog **niet automatisch productie-`countQuality` of '?'**. Bestaande domeinsemantiek eerst delen/hergebruiken of gericht vergelijken; geen tweede statusclassifier promoten.

P2i/2j bewezen **81.945** unieke geselecteerde locaties en exacte celcountpariteit bij een later snapshot (13 gridLevel8-cellen), P2k/2l bewezen **1.608** landelijk gefilterde CCS2/DC-locaties en complete raster-/statuspariteit voor de testfixture (11 cellen, 265,966 ms in de afzonderlijke EXPLAIN). P2h liet zien dat geforceerde geo-first `LATERAL` per geografische kandidaat regionaal veel trager is. **Begin met de oorspronkelijke productie-`MapFilter` SQL-vorm; geen hardcoded plannerdispatch.**

## 2. Eindbeeld en expliciete aannames

Het nieuwe endpoint wordt voorgesteld als `GET /api/v1/map/overview?bbox=...&zoom=...&[MapFilter]`. De JSON is voorlopig contractontwerp, nog geen ingeschakelde implementatie.

- Volledige **canonieke Web Mercator rasterceldekking**, zelfde Q1–Q3-celgrens-/eigendomsformule.
- **500 markerobjecten maximaal per response** als **voorlopige** UI-/payloadbegroting; dit is geen limiet op ruwe laadlocaties. Bij meer dan 500 niet-lege cellen wordt de resolutie grover totdat de volledige dekkingsset past; nooit truncate.
- Lage zoom hele land, middenzoom clusters + individuele markers, hoge zoom overwegend losse locaties; de server retourneert de **daadwerkelijk gekozen** integer `gridLevel`, niet alleen camerazoom.
- Type `cluster`: stabiele `gridX,gridY,gridLevel`, `locationCount`, `locationsWithFree`, `locationsWithNoFree`, `locationsUnknown` en een deterministische positie in de cel. Ring proportioneel op **aantal locaties**.
- Type `location`: stabiele samengestelde `featureId` (string), echte lon/lat, minimale markergetallen en betrouwbare countkwaliteit volgens bestaande regels; overige detailinformatie op aanvraag.
- `coverage`: volledig **feitelijk uitgevouwen** celbereik (`gridLevel,minCellX,minCellY,maxCellX,maxCellY`) en `complete=true` alleen wanneer **geen geselecteerde locatie ontbreekt**. Geen losse records voor lege cellen.
- Een overzicht is pas als `complete` aan te merken wanneer het zowel het objectbudget als de volledige bereik-/populatie-invariant haalt. Bronversheid is onafhankelijk: `feedIsStale` mag true zijn terwijl de berekening zelf volledig is.
- Cache en Android-requestcoördinatie worden strikt gescheiden van feed-observatie. Een betrouwbaar **dataVersion**-mechanisme is nog niet aangetoond; cross-request-merging op verondersteld identieke data blijft tot implementatie geblokkeerd.

## 3. Voorstel voor de PR-opsplitsing

### PR-A — API-contract, DTO's en rastermath; geen endpoint activeren

**Bestanden (beoogd):** `src/ChargeWatch.Api/ApiModels.cs`, nieuwe pure `MapGrid.cs`/`MapOverviewContracts.cs` (definitieve naam na repo-inspectie), optionele API-selftest; bestaande `ApiOptions`-parser uitbreiden zonder huidig endpoint te breken.

- Implementeer wereldvaste `gridLevel`-/celindexfuncties (WGS84→EPSG:3857) met eenduidige extent- en rechtsondergrensregels.
- Definieer requests/responses als getypeerde records; cluster/location gescheiden op `kind`; `featureId` string behouden, `locationId` optioneel afzonderlijk.
- Valideer `zoom` op geldige eindige waarden, bboxgrenzen, lon/lat-bounds en antimeridiaan; voor Nederland mag een overschrijdende bbox voorlopig expliciet `400` krijgen, niet stilzwijgend ombuigen.
- Coveragestructuur bevat werkelijk gevraagde volledige cellen, metadata en complete-status. `500` als instelbare standaard, contracttest voor groter/kleiner gebied.
- **Gate A:** zuivere unit/selftests: hoek/grenspunten, elke locatie precies één cel, hele cellen bij lichte pan, clustercel-ID onafhankelijk van camera-offset, response-JSON en historische samengestelde `featureId` ongewijzigd.

### PR-B — read-only SQL en verborgen/staged overview-handler

**Bestanden (beoogd):** `ApiDatabase.cs` (nieuwe methode `GetMapOverview`), `Program.cs` (nieuw, afzonderlijk endpoint), SQL/DB integration tests.

- Hergebruik `MapFilter.TryParse`, `MapFilter.LocationPredicate(startParameter)`, `AddParameters` **exact zoals huidig**; geparametriseerde bbox/gridwaarden en dezelfde filterbetekenis. Geen gekopieerde handmatige productie-`EXISTS`-filter.
- Queryselectie gebruikt uitsluitend **actieve en gepositioneerde locaties** in volledige, uitgelijnde rasterdekking; geen `ORDER BY id LIMIT 1000` vóór aggregatie.
- Kies bij voorkeur P2i/P2k's connector-first/planner-vriendelijke functionele SQL-vorm; geen per-geografische-locatie `LATERAL`-fence als generieke aanpak.
- Bereken per locatie de bestaande betrouwbare beschikbaarheid **zonder connector-JSON** en zonder dubbel getelde EVSE's. De selectie op connectorfilter is **locatie-inclusie**, niet een filter op de EVSE's in de uiteindelijke marker-`vrij/totaal`.
- Vereis `locationsWithFree + locationsWithNoFree + locationsUnknown == locationCount`. Eén geselecteerde locatie betekent een losse kaartmarker, ook binnen een rasterlaag van landelijk formaat.
- Voeg een querypad toe voor de exacte 1-locatie-marker (samengestelde `featureId`, geografische werkelijke positie, minimale betrouwbare `vrij/totaal`); voorkom N+1 queries per marker en het lezen van connector-/tarief-JSON.
- Voer budgetaanpassing **herberekend op complete celscope** uit; geen SQL-`LIMIT 500` die de kaart incompleet maakt. Beperk het aantal resulterende cellen, niet het aantal onderliggende locaties.
- **Gate B:** vergelijk ongefilterde en CCS2/DC-resultaten met P2j/P2l-referenties op hetzelfde databasesnapshot, iedere statusgroep en elke echte locatie-ID; test 0, 1, 500, 501+ niet-lege celgroepen met synthetische inputs en hoge zoom; geen 999-gelimiteerde oude endpointregressies.
- **Niet implementeren zonder review:** echte vaste statuskwaliteit uit oude `GetMapViewport` versus `ExperimentalAvailability`/actuele classificatie: de bronkeuze moet expliciet aansluiten bij de **nu door Android getoonde betrouwbaarheidsregels**.

### PR-C — serverbudget, cacheversheid en request-errors

- **500 markers maximaal**: iteratief grover rasterschema met expliciet teruggegeven effectieve `gridLevel`. Bepaal eerst een ondergrens boven nul; als zelfs een wereldcel >500 objects onmogelijk blijft door uitvoerwijze, behandel dat als interne fout.
- Definieer de selectie bij fractiezooms en pan-stabiliteit: kies niet telkens op exact <500 als dat oscillatie veroorzaakt; overweeg discrete buckets/hysterese met aantoonbare zoom-UX.
- **Geen** pseudo-`dataVersion = now()`; als er geen atomair gepubliceerde snapshotidentifier bestaat, tijdelijk geen cross-request-cel-merging over potentieel verschillende NDW-polls. Laat viewport-responses atomair in één DB-snapshot berekenen.
- `400` voor ongeldige input; `503/504` of toepasselijke `5xx` voor overbelasting/timeout; altijd duidelijk foutcontract. **Geen** gedeeltelijke `markers` met `complete=true`.
- **Gate C:** grens- en cachegevallen, stale bron versus complete aggregatie, outputbudget, responsebytes en seriële + gelijktijdige NAS SQL/HTTP-latency. Benchmarkcijfers (266 ms/986 ms) zijn geen API-SLA.

### PR-D — Android-clientweergave op featureflag (pas na stabiele API)

- Nieuwe getypeerde overview-client en markerparser, behoud van huidige viewport als terugvalpad voor niet-landelijke situaties. Geen misleidend volledige landelijke fallback via NDW GeoJSON.
- Serverclusters direct als clusters tekenen: grijs centrum `locationCount`; ringhoeken via **numerieke locatieaantallen** groen/rood/unknown. Losse markers gebruiken bestaande `vrij/totaal`-markers.
- Pan/zoom/filter-generations: verouderde requests annuleren/negeren; cache-/filteridentiteit synchroniseren met server; geen dubbele overlappende gridcellen.
- Clustertap: animatie naar complete celbounds met marge voor zoekbalk en resultatenpaneel. Bij exact overlappende posities op maximale zinvolle zoom -> beperkt/gepagineerd keuzeoverzicht (aparte API-actie indien nodig).
- Favorieten blijven **lokaal**; fav-indicator op individu op basis van bestaande samengestelde `featureId`; clusterfavoriet alleen bij gegarandeerd gefilterde membership, anders niet claimen.
- **Gate D:** fysieke Samsung: hele Nederland zichtbaar, aantal ≤500, kleur-/ringverhouding, 1px-pan stabiliteit, direct herladen bij filterwissel, kaartperformance, clustertap, favorieten en sheets; geen regressie van PDOK/GPS en details.

### PR-E — operationele acceptatie en releasebesluit

- Gepaarde test van oud versus nieuw op kleine viewports waar alle individuele locaties zichtbaar zouden moeten zijn; tel unieke IDs en vergelijk beschikbaarheid.
- Nationale volledigheid inclusief **een onafhankelijke telling binnen de werkelijk teruggegeven volledige gridcelcoverage**. Test ook geografische grenspunten, meerdere locaties met exact gelijke coordinaten en overlappende viewportrequests.
- NAS resourcebudget en herhaalde echte HTTP-`p50/p95` onder 1/meer clients, JSON grootte en Android FPS/memory. Meet nadat de API staat; geen aanname dat bestaande SQL-`EXPLAIN` gelijk is aan serverreactietijd.
- Status/FAQ/docs en fail-safe rollback: oude `/map/viewport` onveranderd laten tot succesvolle Android-migratie. NDW fallback mag niet als volledige nationale kaart worden voorgesteld.
- **Gate E:** expliciete gebruikersgoedkeuring voor elke merge en deployment volgens ChargeWatch-werkwijze.

## 4. Technische risico's die vóór PR-B expliciet moeten worden opgelost

| Risico | Concrete oplossing/criterium |
|---|---|
| Bestaande kaart-`featureId` string versus DB `long id` | Contract met beide expliciet scheiden, geen stille typewijziging; mobiele consumptie valideren |
| Historische `count(DISTINCT e.id)` + connectorjoin versus P2i–P2l `count(e.id)` | Geen connector-JSON nodig, maar deduplicatie/unknown en consistentie met echte huidige markerstatus expliciet testen |
| Betrouwbare beschikbaarheidsclassifier | Dezelfde bestaande bronregels projecteren, geen P2-count-expressie automatisch tot nieuwe waarheid promoveren |
| `gridLevel` en markerbudget | 500 voorlopig; selectie bij >500 moet volledig en stabiel blijven (geen truncatie of jitter) |
| Bbox versus complete cellcoverage | Indexeer op canonieke celrange; count grens-/hoekkandidaten; invariant met Q1–Q3 en P2j |
| `dataVersion` | Echte atomair publiceerbare datasetversie nog onbekend; zonder die versie geen onbewezen cross-request-snapshot-pariteit |
| Hoge zoom en overlappende exacte coördinaten | Geen oneindige inzoom; aparte beperkte locatiekeuze na clustertap |
| Nationale P2i benchmark met ~3,3 MB hash-spill | Meenemen in RAM/werkbelastingmetingen; niet automatisch een nieuwe index of statusmaterialisatie ontwikkelen |
| Niet-NL locaties binnen NL-bbox | Concreet dekkingsbeleid kiezen (Nederlandgerichte rechthoek vs `country_code='NL'`) alvorens “heel Nederland” formeel te claimen |

## 5. Wat nu als eerste daadwerkelijk op te pakken

**Aanbevolen eerste implementatie-PR na expliciet akkoord:** PR-A (pure rasterscope-/typecontracten en unit-/selftests), zonder endpoint activeren. Vervolgens PR-B met alleen de read-only serverroute; Android pas na succesvolle API-bewijsvoering. Alle PR's klein, squash-merge alleen na gebruikersgoedkeuring. Documentreview kan plaatsvinden zonder deploy.

**Open reviewbesluiten die implementatie raken:**

1. `500` is een **voorlopig aanvaard uitgangspunt**, geen bewezen optimale grens.
2. Individuele externe `featureId` blijft een string; apart `locationId` alleen als Android-detailpad dat echt nodig heeft.
3. Canonieke cel-ID, feitelijke coverage en dataVersion zijn serververantwoordelijkheid.
4. Het exacte zoom-bucketbeleid, complete celbuffer en atomair datasetversiemechanisme moeten vóór activering van het endpoint worden vastgelegd; geen impliciete defaults.

**Geen automatische merge, NAS-deploy of wijzigingen in collector/NDW/DB-schema.**
