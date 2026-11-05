# ChargeWatch — locatie-dataflow en gerichte opvraagstrategie (#113)

> **Actuele duiding — 5 november 2026:** dit is het **historische onderzoeks- en ontwerpdocument** voor [issue #113](https://github.com/VanHinthem/ChargeWatch/issues/113). De API-overview (PR #117–#122), batched details (#124), gzip (#125) en de Android-cache/widget (#123/#112) zijn inmiddels in `main` gemerged. Verwijzingen hieronder naar *nog geen implementatie*, *draft*, *PR #112 niet mergen*, de oude viewport-favorietendatastroom en open ontwerpkeuzes uit 2–3 november zijn **historische tussenstanden, niet de actuele status**. Voor de resterende NAS-productie-HTTP-/featureflag-/default-kaartmodus-gates: zie [STATUS](STATUS.md) en [ROADMAP](ROADMAP.md). Behoud de metingen en motivatie hieronder als technische geschiedenis.


> **Vervolgstand 3 november 2026:** #113 is na succesvolle P2i–P2l-validaties doorgeschoven naar [het voorstel voor het landelijke map-overview API-/zoom-/coveragecontract](MAP_OVERVIEW_API_CONTRACT_113_DESIGN.md). Dat nieuwe document is de **actuele ontwerpuitwerking**. Historische open vragen en voorgestelde alternatieven hieronder (bijvoorbeeld de reeds gekozen clustercentrum-/ringsemantiek) blijven onderzoekscontext; behandel ze niet als opnieuw openstaande gebruikersbesluiten. Er is nog **geen akkoord op implementatie, merge of deployment**.


**Status:** eerste technische inventarisatie; **landelijke kaartdekking met zoomafhankelijke clusters als productdoel vastgesteld**, nog geen API-contract, clusteringtechniek of implementatiebesluit.  
**Datum:** 2 november 2026.  
**Tracking:** [issue #113](https://github.com/VanHinthem/ChargeWatch/issues/113). Bestaande widget-feature [#101](https://github.com/VanHinthem/ChargeWatch/issues/101) / [draft-PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112) blijft onafhankelijk en wordt niet in deze ontwerpbranch gemerged.

## 1. Het doel

Een voorspelbare, onderhoudbare gegevensarchitectuur waarin kaart, geselecteerde laadlocatie, favorieten en Android-widget **alleen ophalen wat ze nodig hebben**, zonder een tweede implementatie van de EVSE-telregels of onnodige netwerkrequests. De gebruiker heeft expliciet gekozen om deze feature eerst grondig uit te werken en pas na ontwerpgoedkeuring te implementeren.

**Randvoorwaarden:** NDW/OCPI blijft bron van waarheid; de PostgreSQL-catalogus is de ingelezen, laatst bekende snapshot en bevat geen garantie van live-NDW-telling. Onbetrouwbare aantallen niet als 0 presenteren. Een oudere geldige `vrij/totaal` mag groen/rood blijven met duidelijke `Laatst bekend`-aanduiding; `?` staat voor daadwerkelijk ontbrekende/onbetrouwbare data. Read-only server/API, geen accounts, geen wijzigingen in lokale opslag/alias/volgorde tenzij noodzakelijk.

## 2. Gecontroleerde bestaande codepaden

| Gebruik | Eerste gegevens | Bij selectie / verversen | Huidig nadeel |
|---|---|---|---|
| Kaart | `MapViewModel` → `ChargeWatchMapClient.load(viewport, filters)` → `GET /api/v1/map/viewport?bbox=...`; NDW GeoJSON als toegestane fallback | `MapScreen` geeft de reeds ingeladen `MapLocation` aan `LocationSelectionSheet` | **Correct gebruik** van geografische requests; hergebruik de kaartdata bij tikken |
| Compacte sheet | Naam, adres, telling, operator, connectorcategorieën en feedstatus uit geselecteerde `MapLocation` | `LaunchedEffect(selectedId)` start direct `LocationEnrichmentClient.load(featureId)` | Hoewel compact scherm direct verschijnt, vraagt het meteen volledige detail- **en** statistiekendata op |
| Uitgebreide sheet | Zelfde `MapLocation` en `enrichment`-state, alleen meer secties zichtbaar | Omhoogvegen doet op zichzelf **geen extra verzoek**; statistieken zijn al eerder aangevraagd | Historiek wordt mogelijk opgevraagd hoewel gebruiker sheet nooit uitbreidt |
| Favorietenscherm | `FavoritesRepository` bewaart `featureId`, adres, coördinaten en lokale alias | `FavoritesAvailabilityClient.refresh()` doet één of meer `/api/v1/map/viewport`-requests, fallback NDW | Locaties zijn al bij **ID** bekend, maar toch geografisch opgehaald |
| Native widget #101 | Zelfde favorieten, lokale DataStore-cache | Werkende tussenoplossing op draft-PR #112: kleine parallelle viewports en incrementele updates | Nog steeds indirecte kaartopvraging; oplossingskeuze mag deze tijdelijke branch niet onnodig opblazen |

**Feitelijke codeplaatsen:** `android/.../ui/map/MapViewModel.kt`, `MapScreen.kt` (`ChargeMap`, `LocationSelectionSheet`), `data/enrichment/LocationEnrichmentClient.kt`, `data/favorites/FavoritesRepository.kt`, `FavoritesAvailabilityClient.kt`, `data/ndw/ChargeWatchMapClient.kt`; API: `src/ChargeWatch.Api/Program.cs`, `ApiDatabase.cs`.

### De twee aanvullende calls bij een enkele markerselectie

```http
GET /api/v1/locations/by-ndw-id/{featureId}
GET /api/v1/locations/by-ndw-id/{featureId}/statistics
```

De `LocationEnrichmentClient` doet de tweede call **na** de eerste (sequentieel) en rondt de enrichment pas daarna af. De compacte sheet kan daardoor op de uiteindelijke tariefverrijking wachten terwijl voor de compacte weergave de historische grafiek niet nodig is. De bestaande kaarttelling wordt niet bij selectie ververst met de detail-API. Dit is een **kandidaat voor optimalisatie**, geen bewijs dat dit op een concrete telefoon de grootste latencybron is.

### Identiteit

De interne PostgreSQL-`charging_location.id` is een `long`. De Android-favoriet bevat **niet** die interne sleutel, maar `featureId`, opgebouwd uit de OCPI-sleutel (country code, party id, source/location id). De bestaande `GET /api/v1/locations/by-ndw-id/{featureId}` route parseert dit ID en gebruikt `FindLocationId(key)` alvorens `GetLocation(id)` aan te roepen.

Voorkeur voor onderzoek: behoud het stabiele publieke OCPI/NDW-identiteitscontract; valideer de bestaande parser en unieke database-index, match-/missinggevallen, migratie of inactivatie. Gebruik coördinaten **niet** om identiteit te raden.

## 3. Kaart eerst: landelijke dekking en zoomafhankelijke aggregatie (productbesluit 2 november 2026)

### Vastgestelde productuitgangspunten

Na de code-audit van de bestaande kaart heeft de gebruiker twee ideeën voorgesteld en expliciet de landelijke variant bevestigd:

1. **De kaart haalt uitsluitend de minimaal benodigde markerinformatie op**, met **behoud van alle bestaande kaartfilters** en hun EVSE-semantiek. De compacte/uitgebreide detailsheet haalt additionele gegevens doelgericht op.
2. **Nederland moet ook volledig op de kaart kunnen worden getoond bij sterk uitzoomen**, zonder `Zoom verder in` en zonder stilzwijgend locaties kwijt te raken: grotere aggregaties bij lage zoom, een **mix van clusters en individuele locaties** op middelste zoomniveaus, en afzonderlijke locaties bij voldoende inzoomen.
3. De server levert een **beheersbaar aantal visuele kaartobjecten**, onafhankelijk van hoeveel onderliggende laadlocaties zich in het gebied bevinden. De huidige **999-locatiegrens is geen productvereiste** voor dit nieuwe contract; hij blijft alleen gelden voor het oude endpoint tijdens de overgang.
4. Filters (operator, connector type, AC/DC, vermogen, minimaal vrij) worden **vóór clustering** toegepast. Clusteraantallen en kleuren slaan uitsluitend op de gefilterde set; geen filterversoepeling tijdens fallback.
5. NDW blijft de waarheid achter de ingelezen database; bestaande betrouwbare groen/rood/unknown-logica blijft gelden. **Geen implementatie-, merge- of NAS-deployakkoord**: dit is akkoord op doel/UX, niet op algoritme of API-contract.

### Gecontroleerde huidige implementatie en concrete bottlenecks

- `GET /api/v1/map/viewport?bbox=...` selecteert in `ApiDatabase.GetMapViewport` maximaal **1.000 individuele locaties**. Bij `locations.Count >= 1000` geeft `Program.cs` een **HTTP 413** met `Viewport exceeds 999 locations; zoom in.`; de Android-client weigert bewust een onvolledig resultaat als een volledige kaart.
- Het huidige endpoint accepteert geografische oppervlakte tot **1.0 graad²**, met dezelfde maximumgrens ook aan Android-zijde. Dat is geen nationaal dekkend contract.
- De API produceert al onder andere **volledige connectorcategorieën** per locatie; de kaart toont hoofdzakelijk positie, kleur, `vrij/totaal`, cluster en lokale favorietbadge. Onderzoek welke minimale velden voor snelle markerweergave echt essentieel zijn en welke voorlopig nodig blijven voor onmiddellijk compacte sheet.
- Clustering gebeurt vandaag **op Android** in `MapScreen.renderNativeMarkers()`, nadat alle individuele `MapLocation`-records zijn opgehaald. De code hergroepeert markers op schermafstand, doorloopt clusters herhaaldelijk en voert `map.clear()` uit. Dit verhoogt de rekentijd en tekenkosten bij meer locaties.
- Nu toont clustertikken afhankelijk van zoom een zoomactie of selectielijst. De nieuwe server-cluster kent **niet alle individuele onderliggende IDs in de Android-response**; de clusterklik moet daarom bij lagere zoom *inzoomen/gebied navigeren*, en pas bij afzonderlijke locaties de detailsheet openen. Gedrag bij sterk ingezoomde overlappende locaties blijft een UX-onderzoekspunt.

### Kandidaatcontract, nog NIET besloten

```text
GET /api/v1/map/overview?bbox=minLon,minLat,maxLon,maxLat&zoom=<mapZoom>&filters...
    → { coverage, feedLastObservedAt, feedIsStale, markers: [
          { kind: "cluster", clusterId, latitude, longitude,
            locationCount, availableCount?, totalCount?, countQuality? },
          { kind: "location", featureId, latitude, longitude,
            availableCount?, totalCount?, countQuality? }
       ] }
```

Dit is een *schematische richting*; routenaam, exacte velden, foutcontract, cacheheaders en typebepaling zijn nog open. **LocationCount = aantal individuele laadlocaties** in een cluster, **niet** aantal EVSE's of connectoren. Ratio's zijn EVSE-totalen; tel elk operationeel EVSE maximaal één keer, sluit `PLANNED`/`REMOVED` uit en onderscheid onbekende data. Clusterkleur kan niet uitsluitend gebaseerd worden op één ambigu optelgetal: onderzoek betrouwbare populatietelling, partial/unknown mix en staleness. Geen `0` afleiden uit ontbrekende brondata. Bron-/feedwaarneming blijft zichtbaar waar relevant.

### PostGIS-query- en cacheontwerp — onderzoeksuitwerking (2 november 2026)

**Werkhypothese, nog geen implementatiebesluit.** De huidige database bevat al `charging_location.position geography(Point,4326)` met `ix_location_position USING gist(position)`, plus unieke OCPI-identiteit `(country_code,party_id,source_id)`. De huidige `ApiDatabase.GetMapViewport` gebruikt `ST_Intersects` en `MapFilter.LocationPredicate`, beperkt eerst individuele locaties tot 1000 en berekent daarna EVSE-tellingen en connectorcategorieën. De nieuwe overzichtskaart mag **geen LIMIT op ruwe locaties** gebruiken, maar moet wel het aantal te tekenen objecten beperken via zoomafhankelijke aggregatie.

#### Voorgestelde read-only querypipeline

1. **Ruimtelijke selectie:** bepaal een uitgelijnd vast tile-/wereldraster, een gekozen resolutie (zoomBucket/gridCellSize) en een integer-celbereik inclusief beperkte buffer. Gebruik een indexvriendelijke geografische bounding query op `charging_location.position`. Vermijd een raster relatief aan de actuele bbox, want dan springen clusters bij pannen.
2. **Filters op de individuele locatie:** pas exact dezelfde bestaande `MapFilter.LocationPredicate` toe vóór het groeperen. Belangrijk: het huidige technische filter werkt met `EXISTS` op actieve connectoren/operationele EVSE's en `minFree` op unieke EVSE's; zorg dat die semantiek behouden blijft.
3. **Één availabilitystatus per locatie:** bepaal `freeCount`, `operationalCount` (één keer per actieve EVSE, `PLANNED`/`REMOVED` uitgesloten), en `known/unknown`, met hergebruik van bestaande countingregels. Vermijd de volledige `jsonb_agg` van connectorcategorieën wanneer die voor de kaart niet nodig zijn.
4. **Grid-ID:** transformeer locatiecoördinaten naar een **zoomgebonden, oorsprongvaste Web Mercator-cel**; groepeer op integer `gridX,gridY` bij resolutie `gridLevel`. Validatie nodig voor geografische randgevallen, indexefficiëntie en eventuele `ST_SnapToGrid`-alternatieven: snap op ruwe longitude/latitude is niet automatisch pixelstabiel. De exacte PostGIS-functie en formule blijven open.
5. **Aggregatie:** tel per cel `locationCount`, `locationsWithFree`, `locationsWithNoFree`, `locationsUnknown`. `count=1` resulteert in een individuele locatie met featureId en `vrij/totaal`; bij `count>1` in een neutraal clustercentrum met groen/rode/onbekende ring. Gebruik volledige celpopulatie, niet alleen de stukjes die toevallig binnen de scherm-bbox vallen, en voorkom dubbeltellingen in overlappende requests.
6. **Responsebudget:** laat bij veel objecten een **grovere gridresolutie** kiezen of geef een expliciet, controleerbaar contract terug; stuur nooit de eerste N locaties of gedeeltelijke cellen alsof de hele landelijke kaart compleet is. Server geeft de daadwerkelijk gebruikte `gridLevel` en volledige `coverage` door.
7. **Publicatie:** verzend uitsluitend getypeerde mapobjecten + globale feedstatus. Bewaar filterversie en bronobservatie op het juiste niveau; documenteer dat een cachemoment geen nieuwe EVSE-waarneming is.

#### Cachingkandidaten — eerst meten

| Laag | Kansrijke cache | Correctheid/risico |
|---|---|---|
| Android | Bestaande complete tile/celresponses bewaren per gridLevel/filterset/bronversie | Weergeven van oude kaart met duidelijke feedstatus; oude in-flight respons mag filters/zoom niet overschrijven |
| API memory/cache | Hergebruik van populaire grid-/tilequeries, bijvoorbeeld nationaal overzicht | Geldigheid koppelen aan NDW-snapshot/checkpoint en filters; geheugen en cache-explosie bij operatorcombinaties |
| PostgreSQL | Gerichte lichte beschikbaarheidssamenvatting per locatie | Moet atomair overeenkomen met gepubliceerde EVSE-snapshot, en geen stale/unknown als vrij of bezet behandelen |
| Voorberekende zoomlagen | Vooral voor veelgevraagd ongefilterd landelijk overzicht | Gecombineerde dynamische filters maken veel vooraggregaties onpraktisch; invalidatie/operatiebelasting onderzoeken |

**Architectuurwaarschuwing:** `snapshot_poll_checkpoint` toont een feedcontrole, geen garantie dat iedere EVSE-status opnieuw gewijzigd is. Een voorgeaggregeerde kaart of cache mag dus geen nieuwere status suggereren dan de ingelezen dataset werkelijk bewijst. Bij release van nieuwe snapshots moeten alle relevante kaartlagen dezelfde gegevensversie representeren.

#### Vergelijkingsmetingen / beslispoorten

- Huidige indexen verifiëren tegen **productie-schema** (migraties en actuele DB controleren): source `db/init/001_schema.sql` bevat `ix_location_position`; dit bewijst nog niet dat elke index in de NAS precies zo bestaat of door de planner wordt gebruikt.
- `EXPLAIN (ANALYZE, BUFFERS)` vergelijken voor vaste cellen met **1**, **100**, **1000** en **landelijke** aantallen locaties en voor filterloze en selectieve `minFree`/connector- en operatorfilters.
- Vergelijk plan A (live `charging_location` + `evse`), B (per-locatie compacte summary), C (ingelezen gridcache) op SQL CPU/reads, DB-tijd, API p50/p95, payload, Android frame/renderduur en geheugen.
- Test de invariant dat iedere actieve/gefilterde locatie in de complete aangevraagde celset **precies eenmaal** is opgenomen, óók bij pan, gridgrens, dataverversing en gelijktijdige filterwisseling.
- Verzamel duur en aantallen per schaal: straat, Oss/Elst, Randstad/Brabant, heel Nederland, inclusief onbruikbare/onbekende EVSE's, meerdere locaties exact op dezelfde coördinaten en NDW/API-fout.
- Voor echte landelijke dekking mogen de huidige `MAX_VIEWPORT_AREA=1.0` en HTTP-413-cap in het **nieuwe** contract worden vervangen door aggregatie, maar het **bestaande endpoint** blijft ongewijzigd en betrouwbaar tijdens de migratie.

**Voorkeur voor uitvoering ná akkoord:** eerst een read-only proof-of-concept/benchmark zonder schemawijziging om live SQL + gridaggregatie te toetsen; alleen bij aantoonbare DB-kosten aanvullende samenvattingen, indexen of materialisatie ontwerpen. Bestaande API-/Android-gebruikers kunnen het oude contract blijven gebruiken tot expliciet besloten om over te stappen.

---

### Live telling versus per-locatie-samenvatting — technische afweging (2 november 2026)

**Codecontrole:** `BulkImporter.Import` start één PostgreSQL-transactie vóór de bulk-staging en commit pas na de catalogus-/EVSE-/connectorwijzigingen en de bijbehorende import/checkpoint-/statistiekmutaties (`src/ChargeWatch.Importer/BulkImporter.cs`, `BeginTransactionAsync` en `CommitAsync`). Dit maakt een **transactioneel consistente uitbreiding** met locatiebeschikbaarheid in beginsel mogelijk, maar bewijst niet dat een dergelijke tabel al aanwezig is. Voor een v2-maprequest met meerdere SQL-statements is expliciete consistentie nodig: onder standaard `READ COMMITTED` kunnen losse statements namelijk verschillende gecommitte snapshots zien. Voor een momentopname over meerdere queries kan een read-only `REPEATABLE READ`-transactie of een zorgvuldig enkel-statementplan nodig zijn.

| Variant | Querypad | Voordelen | Risico's / toets |
|---|---|---|---|
| **A. Live EVSE-aggregatie** | PostGIS locatie-/filterselectie → actieve EVSE's → beschikbaarheidsstatus per locatie → vaste rastercelgroepering | Geen schema-/importwijziging; bron van telling blijft rechtstreeks de actuele catalogus; snel te prototypen | Landelijke volle kaart en herhaalde filtering kunnen veel EVSE's scannen; zonder index-/planmetingen onbekende p95 |
| **B. Compacte per-locatie status** | Collector onderhoudt `location_availability_summary` in **dezelfde** gepubliceerde transacties → kaart selecteert lichte samenvatting met spatial/filtercondities → rasteraggregatie | Vermijdt bij veel kaartrequests herhaald tellen van dezelfde EVSE's; andere locatie-/widget-endpoints kunnen hergebruiken | Schema-/importwerk, backfill, status-invariant en atomische updates; **technische connectorfilters blijven een aanvullende join/existence-check** |
| **C. Voorberekende clusters/tiles** | Aggregaties per zoomcel/cache vastleggen en incrementeel invalideren | Veel sneller hergebruik bij landelijke kaart en veel clients | Combinatorische filtersets, invalidatie bij EVSE-statuswijziging, bronversheid, opslag en verhoogde importerbelasting |

**Bewust geen vooraf gemaakte keuze:** begin met A als gecontroleerde baseline/prototype en meet B als echte verbetering alleen wanneer A onvoldoende presteert. C is pas zinvol bij aantoonbare server-/clientbelasting. Onderzoek bovendien een hybride variant: lichte **per-locatie numerieke status** plus live `EXISTS` voor technische filters. Dan blijft de statusring gebaseerd op de **hele laadlocatie** zoals nu, terwijl het filter slechts selecteert *welke locaties* meetellen. Een `minFree`-filter met connector/powerfilter moet op de **passende operationele EVSE's** blijven rekenen en kan niet zonder meer door één totale availableCount uit een samenvatting worden vervangen.

**Veiligheid van statusinformatie:** `AVAILABLE` is de groene EVSE-status; operationele capaciteit telt actieve EVSE's behalve `PLANNED` en `REMOVED`; nul totale capaciteit of incomplete/onbetrouwbare status is niet automatisch een rode locatie. Eenzelfde locatie mag niet groen in kaart en grijs in bulk/favorieten worden door uiteenlopende semantiek. Bepaal expliciet of onbekende EVSE-statussen op een locatie tot `locationsUnknown` moeten leiden; hergebruik bestaande betrouwbaarheidstests, voeg regressietests voor partial source toe.

**Opmerkingen over fysieke indexen:** init-schema bevat `ix_location_position` GiST en unieke `evse(location_id,source_uid)` (waardoor via B-tree-prefix een lookup per locatie mogelijk is). Dit betekent **niet** dat bij een grote, ongefilterde landelijke aggregatie noodzakelijk een indexscan optimaal is; de planner kan terecht een sequential scan of hash-join kiezen. Verifieer werkelijke NAS-indexen en meet `EXPLAIN (ANALYZE,BUFFERS)`; nieuw `evse(status)`- of summary-index niet vooraf verzinnen.

#### Benchmarks en bewijscriteria

- **Representatieve API-queries:** ongefilterd NL en regio; operator-only; `minFree=1/2`; CC(S)2/AC/DC/vermogen gecombineerd; enkele dense stads-tiles; 1000+ records zonder afkapping.
- **A/B-metingen:** tijd in geografische selectie, locatie-filter, EVSE-aggregatie en gridgroepering afzonderlijk; SQL buffer hits/reads, CPU, responsebytes, p50/p95 en MapLibre frametijd op fysieke Samsung. Meet cold/warm cache en verschillen tussen ingelezen data- en feedpollversie.
- **Consistentie:** tel alle geselecteerde `location_id` exact eenmaal, controleer filtermembership tegen huidige `MapFilter` en statuscounts tegen huidige kaart; verifieer nieuwe tabeltransacties bij rollback, mislukte/partial import, inactivatie, verdwenen EVSE, null/onbekende status en gelijktijdige lezers.
- **Kwaliteit:** maak onderscheid tussen laatste **bronstatuswijziging**, geaccepteerde **feedwaarneming** en aantoonbare **gepubliceerde data-/catalogusversie**; een unchanged poll is geen nieuw EVSE-statusbewijs. Maak geen onware claims over verse individuele EVSE's.
- **Beslisregel:** accepteer A zonder nieuwe tabel zolang de meetbare prestaties en CPU-/batterijlast voldoen; ontwerp B pas bij aantoonbare winst en voorzie een veilige backfill/upgrade; C pas bij verdere bewezen schaalproblemen.

Dit is een **ontwerp-/meetplan, geen uitgevoerde PostgreSQL-benchmark en geen toestemming voor een migratie**.

---

### Clusteringopties en afweging

| Optie | Gedrag | Plus | Onderzoek/risico |
|---|---|---|---|
| A. Dynamische **server-side rasterclustering** o.b.v. bbox + zoom | SQL/PostGIS levert zichtbare clusters én losse locaties | Klein payloadbudget, eigen endpointcontract en geleidelijke migratie | Gridstabiliteit bij pannen/zoom, randcellen/dubbeltellingen, DB CPU bij veel concurrente requests |
| B. Vooraf geaggregeerde zoom-/gridlagen | Materialisatie/caching per zoomgebied en bronstatus | Snel voor landelijke overview | Filters maken vooraf aggregaties moeilijk; verversing/feed-actualiteit en opslag |
| C. **Vector tiles/MVT** en MapLibre-clusterlayers | Ruimtelijke tiles naar client, markertekening GPU-vriendelijk | Potentieel schaalbaar en cachebaar | Grote Android/UI-refactor, interactie/clicktargets/labels, filtervarianten, datakwaliteit |
| D. Meer locaties ophalen, Android-clustering behouden | Huidige API-limiet verhogen | Kleine eerste wijziging | Grote payloads, CPU/GC en rendering blijven bottleneck; voldoet niet goed aan landelijke dekking |

**Werkhypothese voor de benchmark:** eerst A onderzoeken en afzetten tegen C, zonder de beslissing al vast te leggen. Beheerbare kaartobjecten als **budget** is iets anders dan onderliggende locaties trunceren. Als een API-bestek niet kan worden gehaald, mag hij **niet** stilzwijgend relevante delen van Nederland laten wegvallen: zoomniveau/aggregatie verhogen, duidelijk statuscontract of gecontroleerde fout, te valideren.

### Eerste interactie-/clusterregels — voorstel ter UX-review (2 november 2026)

Deze sectie werkt de **bevestigde landelijke zoomdoelen** uit. De clusterweergave, labels en kleurinterpretatie hieronder zijn **aanbevelingen**, nog geen expliciet akkoord van de gebruiker. Huidig gedrag ter vergelijking is gecontroleerd in `ClusterAvailability.kt`, `MapInteraction.kt` en `MapScreen.kt`.

#### A. Rasterresolutie en objectidentiteit

1. Gebruik bij voorkeur een **wereldwijd vast raster in Web Mercator/tilecoördinaten**, met zoomafhankelijke celgrootte, niet een steeds opnieuw op de actuele scherm-bbox gecentreerd raster. Daarmee springen clusters minder bij een kleine pan.
2. Vraag een op volledige cellen/tiles **afgerond gebied met kleine buffer** op; groepeer altijd op dezezelfde vaste cellen. Laat de client alleen de werkelijk zichtbare cellen renderen. Een cluster telt alle gefilterde locaties **in zijn volledige cel**, zodat de teller niet verandert door een paar pixels camera-pan.
3. Elke cel met **precies één** actieve, gefilterde locatie wordt een individuele marker; een cel met **meerdere** wordt een cluster. Lage zoom heeft grotere cellen, hoge zoom kleinere. Geen harde handmatig gedefinieerde straal in kilometers als universele grens.
4. De cel-/clusteridentiteit is tijdelijk en gebonden aan `zoomBucket + gridCell + filterHash + dataVersion`; niet verwarren met de stabiele publieke `featureId` van een locatie. Klikacties gebruiken beschikbare **celbounds**, niet een database-ID als cluster.
5. Niet alle punten worden vanzelf individueel bij hoge zoom: verschillende locaties met **exact dezelfde coördinaten** kunnen in één cel blijven. Hiervoor is een eindige, gepagineerde locatieselectie nodig in plaats van oneindig inzoomen.
6. De invariant over volledigheid moet gelden voor **de aangevraagde, disjuncte tile/celverzameling**, inclusief de buffer. Nooit `locationCount` bij overlappende celresultaten dubbel sommeren. Nog te bepalen: overlap, grensgevallen, laagwissel en stabiel cachen.

#### B. Bepalen van label en kleur

**Huidig gedrag:** `ClusterAvailability.summarizeCluster()` geeft een exacte `vrij/totaal`-som van operationele EVSE's alleen wanneer **alle** samenstellende locaties geldige, niet-ambigue tellingen hebben; anders `?`. Gecombineerd krijgt de cluster groen bij `available>0`, rood bij `available=0` met positieve capaciteit, anders grijs. Dit blijft de betrouwbaarheidsgedachte voor een exacte ratio.

**Voorstel voor landelijke UX (nog ter goedkeuring):**

| Object | Middenlabel | Kleur/ring | Betekenis |
|---|---|---|---|
| Cluster van N locaties | **N** | Ring met groen aandeel locaties met ten minste één betrouwbare vrije EVSE, rood aandeel locaties met betrouwbaar nul vrij, afzonderlijk onbekend aandeel | N is **aantal laadlocaties**, geen aantal EVSE's |
| Individuele locatie | **vrij/totaal** of **?** | Bestaande groene/rode/onbekende marker | Telling van operationele EVSE's op die ene locatie |
| Kleine cluster (optioneel) | Ook **N**, geen wisselend labeltype | Zelfde ring als grote clusters; details bij inzoomen | Semantisch consequent bij elke zoom |

**Voorgestelde onderliggende aggregaatvelden:** `locationCount`, `locationsWithFree`, `locationsWithNoFree`, `locationsUnknown`, `availableEvseCount?`, `totalOperationalEvseCount?`, `countsComplete`. De laatste twee EVSE-aantallen gelden alleen als **alle** samenstellende locaties betrouwbare aantallen hebben. Somcontrole:
`locationsWithFree + locationsWithNoFree + locationsUnknown == locationCount` en `0 <= availableEvseCount <= totalOperationalEvseCount` wanneer `countsComplete=true`. Een cluster met 1 bekende vrije locatie en 200 onbekende locaties wordt nooit geclaimd als `1/200` EVSE's.

Kleursemantiek voor ringsegmenten is gebaseerd op **aantal locaties in statuscategorie**, niet op EVSE-capaciteit; voorkom dat één grote laadlocatie de kleuren van een heel stadscluster domineert. Een volledig groene ring betekent dat **alle** meegenomen locaties minstens één betrouwbaar vrije EVSE hebben. Volledig rood betekent dat alle locaties betrouwbare tellingen hebben en er geen enkele vrije EVSE is. Een ring met onbekend aandeel mag dit niet als volledig bezet/volledig vrij presenteren.

**Filtercontract:** `MapFilter.LocationPredicate` werkt op locatie-/EVSE-existentiële condities. **Eerst** filteren, **dan** exact dezelfde status-/clusteraggregatie; `minFree` telt bij technische filters passende beschikbare EVSE's, terwijl de bestaande mapbadge de totale operationele locatiebeschikbaarheid toont. Niet ongemerkt overschakelen naar *alleen connector-match*-tellingen zonder apart productbesluit.

**Bronversheid:** `feedLastObservedAt`, `feedIsStale`, bronstatus en client-fetchmoment zijn onderscheiden. Een cluster met uiteenlopende bronkwaliteit mag geen uniforme `recent`-claim doen; onderzoek of `unknownCount`, `staleCount` of `dataQuality` in de compacte respons nodig zijn.

#### C. Clustertap en sheetgedrag

| Gebaar/object | Voorgesteld gedrag |
|---|---|
| Tap losse laadlocatie | Compacte sheet onmiddellijk op basis van reeds ontvangen minimumdata; overige details doelgericht op ID laden |
| Tap cluster op lage/middelste zoom | Animatie naar **bounds van de clustercel**, met marge voor zoekbalk/panel; inzoomen totdat clusters kleiner worden; geen volle lijst van N locaties ophalen |
| Tap cluster op maximale bruikbare zoom | Als nog meerdere locaties in dezelfde cel/coördinaat vallen: compacte **locatiekeuzelijst** via een beperkt/pagineerbaar ID- of bbox-contract; geen onbegrensde response |
| Pan of zoom tijdens laden | Laat geldige oudere markers waar passend staan tot nieuwe complete celverzameling beschikbaar is; oude responses mogen na cameraverandering of filterupdate niet over nieuwere heen tekenen |
| Kaartfilter veranderen | Cluster-/tile-cache per **genormaliseerde filterset** scheiden; geen verouderde ongefilterde kaart tonen alsof nieuwe filters toegepast zijn |

**Lokale favorieten:** een individuele bekende favoriet behoudt de eigen gele ster. Een servercluster weet niet welke favorieten lokaal opgeslagen zijn. Onderzoek hoe lokaal bekende favorietcoördinaten aan vaste rastercellen kunnen worden gekoppeld **zonder favoriete IDs of aliases naar de API te sturen**. Toon alleen een cluster-favorietindicatie als zeker is dat die locatie **in de gefilterde set** zit; anders geen misleidende ster. Eventuele aparte overlaypins mogen clusters niet dubbeltellen.

#### D. Responsgrootte, caching en verificatie

- Verzend **lichte, getypeerde kaartobjecten** `location | cluster`, met alleen noodzakelijke attributen. **Clusters bevatten niet alle kindlocaties**; details worden pas na keuze opgevraagd. Een klant mag nooit `MapLocation` voor een cluster fingeren.
- Server bewaakt een **te tekenen objectbudget** door voldoende grof te aggregeren. Geen `LIMIT N` op onderliggende locaties vóór clustering, geen stilzwijgend ontbrekende markers; als de gekozen zoom/celresolutie te veel objecten oplevert, is een expliciet contract voor server-side resolutieaanpassing/overzicht of zichtbare fout nodig.
- Beoordeel de impact van ruimtelijke PostGIS-indexen, unieke EVSE-tellingen, gematerialiseerde statussamenvattingen, tile-cache en filtercombinaties. Geen claims over optimale querykosten zonder `EXPLAIN ANALYZE` op echte data.
- Controleer bij uiteenlopende zoomniveaus, dichtbevolkte steden en landelijk overzicht dat **alle relevante locaties exact eenmaal** in de volledige celdekking zijn opgenomen; na filteractie moeten clustertelling en individueel ingezoomde leden overeenkomen.
- Voor de NDW-fallback is nog geen gelijkwaardig geaggregeerd landelijk endpoint aanwezig. De app mag bij storing **niet** stilzwijgend een incomplete landelijke kaart teruggeven. Ontwerp zichtbare degradatie met cache en/of begrensde lokale fallback.
- Laat fysieke Samsung-kaartinteractie (zoomanimatie, tapvertraging, markerstabiliteit, zoekbalk/panel-ruimte en lokale favorieten) een aparte acceptatiestap zijn.

### Besluit clustervormgeving — neutraal centrum met proportionele beschikbaarheidsring (2 november 2026)

**Door gebruiker gekozen richting:** de cluster toont **uitsluitend het aantal laadlocaties in een neutraal grijze cirkel**; de **buitenring** toont de procentuele verdeling van de **beschikbaarheidsstatus van locaties**. Voorbeeld: als van 100 locaties er 90 minstens één betrouwbare vrije EVSE hebben en 10 betrouwbaar geen vrije EVSE, bestaat de ring uit 90% groen en 10% rood. De gebruiker heeft de grijze cirkel met een proportionele ring als gewenste UX voorgesteld; de volgende technische preciseringen vormen de designuitwerking en blijven te testen.

- **Noemer = alle locaties in de cluster na toepassing van kaartfilters**, niet het aantal EVSE's of connectors. Een locatie met twintig vrije laadpunten telt eenmaal in het groene aandeel, net als een locatie met één vrije EVSE.
- **Drie disjuncte groepen:** `locationsWithFree` (groen: betrouwbare availableCount > 0), `locationsWithNoFree` (rood: betrouwbare availableCount = 0 en total > 0), `locationsUnknown` (neutraal grijs: onbetrouwbare of ontbrekende status). Elke onderliggende locatie hoort bij precies één groep. De som is exact `locationCount`.
- **Onbekend is geen rood.** Bij 70 groen, 15 rood, 15 onbekend op 100 locaties: 70% groene boog, 15% rode, 15% afzonderlijk grijze boog. De neutrale centrumkleur blijft onafhankelijk van beschikbaarheid. Overweeg contrast/aanvullende toegankelijkheidsindicatie op ringsegmenten (niet alleen kleur).
- **Exacte serveraantallen** als gehele integers teruggeven; Android berekent de sectorhoeken (`360 × count/locationCount`) zonder voortijdige percentages af te ronden. Geen schijnprecisie of visuele 100%-claim op basis van gedeeltelijk bekende data.
- **Individuele marker blijft `vrij/totaal` groen/rood/`?`** met de reeds afgesproken semantiek. Clustercentrum wordt niet tot ratio omgeschakeld bij kleine clusters.
- Bezettingsverhouding zegt `aandeel locaties met minstens één vrije EVSE`, niet `percentage vrije EVSE's`, `laadkans` of `momentane laadcapaciteit`. Bronversheid en gefilterde dataset blijven onderdeel van de volledige responsekwaliteitsregels.
- **Nog uit te zoeken:** grootte van de ring op diverse scherm-DPI's, zeer kleine segmenten (bijv. 1%), leesbare labels bij 1/10/1.000+ locaties, toegankelijkheid, optreden bij stale feed en performance van icon/rendercache in MapLibre.

Dit **vervangt** de eerdere open vraag of kleine clusters de huidige EVSE-ratio moeten blijven tonen: de gebruiker wil één consistente semantiek, met locatieaantal in het midden en de statusverdeling in de ring. Er is **nog geen akkoord voor API-/Android-implementatie**.

**Open beslispunt voor de gebruiker:** op clusters altijd **aantal laadlocaties in het midden + statusverdeling in een groen/rode/onbekende ring** (aanbevolen voor nationale schaal), of op kleine clusters vasthouden aan de huidige **EVSE-vrij/totaal**-ratio? Het eerste is consequent schaalbaar; het tweede geeft meer directe EVSE-informatie maar past bij zeer grote clusters minder goed en kan van betekenis wisselen bij zoomen.

---

### UX- en databetrouwbaarheidspunten die nog uitgewerkt moeten worden

- **Zoom & pan:** landelijke begin-/uitzoomkaart mogelijk; clusters vallen steeds verder uiteen tot individuele locaties. Stabiele clusterposities over pan-/zoomovergangen, geen markerflits of onverwachte lege regio's.
- **Clustertik:** bij lager zoomniveau gericht inzoomen op clusterregio; pas een enkele locatie opent de bestaande compacte sheet. Bij maximumzoom en meerdere exact samenliggende locaties moet een expliciete selectie mogelijk blijven (niet eindeloos inzoomen).
- **Favorieten:** de server kent geen lokale favorieten. Overweeg client-overlay van lokale favorietmarkeringen, voorkom dubbele pins bovenop clusters en pas kleur/sterregels consistent toe. Geen alias of lijst naar server versturen.
- **Filter-pariteit:** clusterlocaties én aantallen komen voort uit exact dezelfde gefilterde dataset als een volledig ingezoomde kaart; voorwaarden voor `minFree` en connectorfilters niet verwarren met de in de badge getoonde *volledige* EVSE-ratio. Onduidelijke filtersemantiek expliciet testen.
- **NDW-fallback:** directe NDW GeoJSON biedt niet vanzelf hetzelfde server-geaggregeerde contract. Ontwerp een zichtbaar degradatiepad zonder stilzwijgend ongefilterde/partiële clusters te tonen.
- **Minimale kaartdata versus direct bruikbare sheet:** een lightweight object kan slechts coords, featureId, aantallen, kwaliteit en bronversheid dragen. Bepaal de minimale *gewenste sheet-first-frame*, en of beperkte naam/adresinformatie eventueel in een cache mee moet om plotselinge lege compacte sheets te voorkomen.
- **Rekenkosten:** vermijd per pan opnieuw alle EVSE/connector-joins over de hele Nederlandse catalogus. Onderzoek index- en queryplannen, cachingstrategie, rastercelresolutie, huidige `MapFilter`-predicate en eventuele materialisatie; geen nieuwe index/schema zonder meetresultaten.

### Kaartnavigatie, prefetch en cache — UX- en technische ontwerprichting (2 november 2026)

**Gebruiker akkoord met verder uitwerken**, niet met implementatie of definitieve getallen. Doel: een kaart die tijdens pannen en zoomen stabiel blijft en alleen ontbrekende geografische cellen opvraagt.

#### UX-contract (voorgesteld)

1. **Tijdens slepen/pinchen:** laat het laatst geldige beeld staan; geen wit/lege kaart of opzettelijk `map.clear()` voordat vervangende data beschikbaar is. Het basiskaartbeeld beweegt gewoon met de camera mee. Verouderde laadmarkerstatus mag niet als verse meting worden voorgesteld.
2. **Camera idle:** laad nieuwe data op basis van de geëindigde camera (`bbox`, `zoomBucket`, filters). Onderdruk duplicaten en voorkom verzoeken bij onbeduidende bewegingen die binnen volledig gedekte cachecellen blijven. De bestaande 500 ms-debounce is een *huidige waarde*, geen gekozen nieuwe SLA.
3. **Prefetch:** haal complete tile-/gridcellen op die in beeld liggen plus een **kleine configureerbare ring aangrenzende cellen**; dit maakt korte verplaatsingen vaak cache-only. De precieze omvang (nul/één/meerdere ringen, afhankelijk van netwerk en zoom) moet worden gemeten, niet nu gefixeerd.
4. **Zoomen:** toon tijdelijk het geldige oude raster terwijl nieuw niveau wordt geladen; verwissel **atomair per complete coverage-set** of met ondubbelzinnig clip-/coverage-beleid. Een cluster en zijn losse kinderen mogen niet tegelijk tellen/tekenen als doublures. Bij een cachehit voor het nieuwe zoomniveau mag de transitie direct plaatsvinden.
5. **Filters wijzigen:** cache op genormaliseerde filterhash scheiden; resultaten van de oude filters niet ongemerkt tonen als resultaten van nieuwe filters. Een expliciete loadingindicator en duidelijk overgangsgedrag zijn nodig, zonder verzonnen of niet-matchende markers als definitieve resultaten.
6. **Fouten/offline:** laatst bekende kaartdata mag zichtbaar blijven met duidelijke cache-/fout-/bronindicatie en zonder te suggereren dat het nieuwe gebied compleet is; cachemissgebieden blijven expliciet niet geladen. De huidige echte NDW-geojsonfallback moet nog worden vergeleken met een clustered landelijke API; nooit gedeeltelijke kaart als volledige dekking presenteren.
7. **Markers en sheets:** dezelfde wereldwijde grid-/celsleutels geven stabiele identiteit tijdens kleine pans. Een geopende individuele detailsheet blijft gekoppeld aan de publieke `featureId`, ook als marker-zoomniveau verandert; selectie en lokale favorieten gaan niet verloren bij een cache-update.

#### Schets van cache-identiteit en invalidatie

`(apiContractVersion, gridLevel, tileX, tileY, normalizedFilterHash, publishedDataVersion)` -> volledige, getypeerde `location | cluster`-inhoud van die cel/tile.

- **Cache-entry is een complete cel:** persistente voorkeur is niet noodzakelijk in v1; begin met begrensde in-memory LRU en meet geheugen/allocaties. Eén request mag meerdere cellen omvatten mits de responscellen duidelijk identificeerbaar en volledig zijn.
- **Data version/TTL:** cache de geografische *lidmaatschaps-/positioneringslaag* mogelijk langer dan bezettingsaggregaten. Beschikbaarheidspercentages kunnen veranderen na een NDW-import en vereisen een betrouwbaar snapshot-/versiecontract, age-indicator of targeted refresh. `feedLastObservedAt` alleen is geen bewijs dat individuele EVSE-statussen veranderd zijn.
- **Stale-while-revalidate:** oude correcte posities en tellingen mogen tijdelijk zichtbaar blijven als `Laatst bekend`; revalidatie mag niet tot flikkerende lege frames leiden. Laat niet twee verschillende filter-/datasetversies als één gezamenlijk actueel resultaat doorgaan.
- **In-flight deduplicatie:** meerdere aanvragen voor dezelfde tile/filter/versie delen één operatie. Nieuwe camera-/filterrequest mag oude response niet als de huidige weergave installeren. Annuleren moet efficiënt zijn, maar resultaten die nog geldige cachecellen opleveren kunnen eventueel gecachet worden zonder UI-overschrijving.
- **Budget:** beperk zowel aantallen tegelijk zichtbare kaartobjecten als hoeveelheid prefetch-/cachegegevens; geen onbeperkte tile-ring op landelijk zoomniveau, geen onbegrensd aantal serverqueries of remote downloads.
- **Camera-afhankelijk:** serverbepaalde gridLevel moet terugkomen in de response. Een viewport mag niet worden gemarkeerd als compleet wanneer sommige gevraagde cellen ontbreken of de API een resolutie degradeerde.

#### Tests en metingen voor de navigatiekwaliteit

- Kleine pan binnen gecachte cellen: **nul nieuwe netwerkrequests** zolang data geldig is; slepen blijft vloeiend.
- Pan over celrand: alleen werkelijk ontbrekende cellen aanvragen, reeds gecachte objecten behouden; exact één representatie per locatie binnen de nieuwe coverage.
- Snel heen-en-weer pannen: geen response-race, dubbele markers, oneindige requests of terugschakelen naar verouderde filters.
- Zoom 4 → 7 → 12 → 17 en terug: stabiele clusters/individuele locaties, bruikbare cross-zoom-cache en geen zichtbare lege kaart tijdens normale overgang.
- Activatie/uitzetten filter, donker/licht thema, favorietenoverlay, zoekpin, geopend detail, gesimuleerde offline-/timeoutstatus.
- Meet netwerkcalls per beweging, responsebytes, cachehitratio, end-to-end tijd tot correcte kaart, frame-renderlatentie/stutters, API p50/p95 en batterij-/geheugenkosten. Stel grenzen pas na fysieke Samsung- en NAS-metingen vast.

**Ontwerpadvies:** begin met complete vaste tiles en één configureerbare prefetch-ring als benchmarkscenario, maar behandel dat *niet* als vaste productparameter. Kies eerst een coherente coverage-/versioningstrategie; anders kan een ogenschijnlijk snelle cache onderling inconsistente clusterkleuren of dubbele locaties tonen.

---

### Meet- en acceptatiekader (nog geen getallen gefingeerd)

1. Baseline huidige `/map/viewport` op een straat, Oss, druk stadscentrum en groot gebied: p50/p95 API, bytes, querytijd, Android-renderduur, geheugen en aantal individuele punten.
2. Proefopzet voor landelijk overzicht, regionaal niveau en straatniveau: aantallen **te tekenen objecten** versus werkelijk aanwezige/gefiterde locaties, SQL `EXPLAIN (ANALYZE, BUFFERS)`, API p50/p95, bytes en MapLibre redraw/camera stutter.
3. Invarianten: de som van `locationCount` over disjuncte clusters plus losse markers is gelijk aan alle actieve, gematchte locaties in het getoonde gebied (met helder randbeleid), zowel ongefilterd als met representatieve combinatiefilters. Geen dubbeltellingen of verborgen restresultaten.
4. Fouten: onbekende EVSE-status, stale feed, lege filtering, veel locaties op één coördinaat, cluster op tile-/bboxrand, pan/zoom-inflight races, snelle filterwissel en falende API/NDW.
5. Pas na meting grenzen voor kaartobjectbudget, zoomresoluties, clustering- of cachetechniek, responstijd en eventuele landelijk zichtbare data-aggregatie kiezen.

**Prioriteit binnen #113:** de gebruiker wil **eerst de kaartlaag** grondig ontwerpen en optimaliseren; hierna pas de gerichte sheet- en favorieten-/widgetrequests. Maak de API-aggregatie eventueel een afgebakende volgende PR, niet in widget-PR #112.

---

## 3. Mogelijke architectuur — voorstellen, geen besluiten

| Alternatief | Wat verandert? | Voordeel | Risico/uitzoekwerk |
|---|---|---|---|
| A. Directe bestaande details per favoriet | Hergebruik `/locations/by-ndw-id/{id}` 1–5 keer | Geen nieuw API-contract of deployment nodig | Detailpayload en DB-joins voor tarieven/EVSE's; extra HTTP-requests, relatief duur |
| B. Lichtgewicht bulkstatus per bekende ID | Bijv. `GET /api/v1/locations/availability?featureId=...&featureId=...` | Eén request voor alle geselecteerde IDs, geen bbox en alleen noodzakelijke velden | Nieuw contract, read-only SQL en test-/deploytraject; semantische consistentie met kaart blijft essentieel |
| C. Algemene samenvattingsroute + optionele velden | Deel dezelfde samenvatting tussen kaart, per-ID en bulk | Minder varianten/duplicatie in client en backend | Risico op te generiek/complex contract; versiebeheer/filtersemantiek |
| D. Sheets gefaseerd verrijken | Kaartdata onmiddellijk, compacte tariffs indien nodig, statistieken alleen bij uitbreiden | Minder onnodig statistiekennetwerk en snellere informatiebeschikbaarheid | UI-races, cancelation, cache, loadingstates en bronnen in dezelfde sheet |

**Werkhypothese:** kaart blijft viewport-gebaseerd; bekende IDs krijgen een gerichte samenvatting; volledig detail en statistieken worden doelgericht en eventueel lazy geladen. De uiteindelijke contractvorm kiezen we op basis van metingen en een afgesproken UX-flow.

## 4. Voorstel API-contract kaart v2 — clusters en individuele locaties (2 november 2026)

**Status: concreet reviewvoorstel, geen definitief goedgekeurde route of implementatie.** Het eerder bevestigde productdoel (heel Nederland zonder stille truncatie; clustercentrum toont aantallen locaties en een beschikbaarheidsring) staat vast. De precieze contractvorm en cachingmechaniek hieronder zijn ter toetsing.

### Endpoint en request

```http
GET /api/v1/map/overview?bbox=minLon,minLat,maxLon,maxLat&zoom=7.5&connectorType=ccs2&minFree=1
Accept: application/json
```

- `bbox` bepaalt de benodigde complete grid-/tilecellen; `zoom` bepaalt de **gewenste** resolutie. De API kan een grovere resolutie kiezen om zijn te meten objectbudget te respecteren en vermeldt die expliciet.
- Alle bestaande `MapFilter`-queryparameters, normalisatie en booleaanse semantiek blijven identiek, inclusief operatorselectie, connectorType, mode, power en minFree. Filteren gebeurt **vóór** aggregatie.
- De API controleert coördinaten, toegestane zoomwaarden, anti-meridian/wereldranden en de maximale *berekeningslast*. De huidige `bbox`-oppervlaklimiet van 1.0 graad² is geen harde nieuwe productgrens. Grote gebieden mogen niet worden afgekapt maar moeten een passende aggregatieresolutie krijgen.
- Deze route blijft GET/read-only. Het bestaande `/api/v1/map/viewport` blijft backward-compatible voor oudere Android-clients en voor een expliciet toegestane migratiefallback.
- Voor cluster-/tilecache is een **canonieke filterrepresentatie** vereist; `filterHash` is een serverbevestiging, geen beveiligingsmechanisme. Een gewijzigde set filterparameters vormt een andere cachedataset.

### Illustratieve JSON response (fictieve aantallen)

```json
{
  "schemaVersion": 2,
  "coverage": {
    "complete": true,
    "gridLevel": 7,
    "cellIds": ["7/65/42", "7/65/43"],
    "bounds": [4.1, 51.2, 5.5, 52.1],
    "filterKey": "canonical-filter-key",
    "publishedVersion": "catalog-snapshot-version",
    "locationCount": 101
  },
  "feed": {
    "lastObservedAt": "2026-11-02T18:00:00Z",
    "isStale": false
  },
  "items": [
    {
      "kind": "cluster",
      "id": "7/65/42",
      "latitude": 51.9,
      "longitude": 5.0,
      "bounds": [4.1, 51.5, 4.8, 52.1],
      "locationCount": 100,
      "locationsWithFree": 70,
      "locationsWithNoFree": 15,
      "locationsUnknown": 15
    },
    {
      "kind": "location",
      "featureId": "NL-ABC-location-123",
      "latitude": 51.77,
      "longitude": 5.52,
      "availableCount": 1,
      "totalCount": 2,
      "countReliability": "safe",
      "sourceUpdatedAt": "2026-11-02T17:58:00Z"
    }
  ]
}
```

**Belangrijk:** dit voorbeeld is **schematisch**. De `coverage.cellIds` zijn illustratief en nog geen echte Web Mercator-tegelindices; `bounds` en aantallen zijn eveneens fictief. De voorbeeldresponse toont slechts twee cellen en laat daarmee een **rekenkundig consistente fictieve dekking** zien: 100 locaties in het cluster plus één losse locatie = `coverage.locationCount` 101. De illustratieve coördinaten en celindices zijn nog geen geldige geografische testfixture; daarvoor moeten de cellen, bounds en posities geometrisch kloppen.

### Betekenis van de belangrijkste velden

| Veld | Contractbetekenis |
|---|---|
| `kind` | Onderscheid tussen **cluster** en **individuele laadlocatie**; Android mag cluster nooit behandelen als een `MapLocation` |
| `locationCount` cluster | Exact aantal actieve locaties dat aan de filters voldoet binnen de volledige cel |
| `locationsWithFree` | Locaties met betrouwbaar `availableCount > 0` |
| `locationsWithNoFree` | Locaties met betrouwbaar `availableCount == 0` en `totalCount > 0` |
| `locationsUnknown` | Locaties zonder betrouwbare status; nooit bij rood optellen |
| `availableCount` / `totalCount` op locatie | Bestaande operationele EVSE-semantiek, niet aantal connectoren |
| `countReliability` | Onderscheid Safe/Estimated/Ambiguous en ontbrekende aantallen; definitieve mapping op server valideren |
| `feed.lastObservedAt` | Laatste geaccepteerde landelijke NDW-poll, **niet** fysieke bevestiging per EVSE |
| `feed.isStale` | Feedversheid, niet hetzelfde als ouderdom van een individuele status |
| `coverage.gridLevel` | Daadwerkelijk gebruikte rasterresolutie; kan afwijken van gevraagde zoom |
| `coverage.complete` | Alleen true als **iedere vereiste complete cel** in dezelfde consistente dataset vertegenwoordigd is |
| `coverage.publishedVersion` | Te ontwerpen dataset-/snapshotidentiteit voor samenhangend cachen en consistente updates |
| `coverage.filterKey` | Gecanoniseerde filterset waarop tellingen en dekking zijn gebaseerd |

### Verplichte integriteitsregels

1. Per cluster: `locationsWithFree + locationsWithNoFree + locationsUnknown == locationCount` (gehele getallen, allemaal >= 0).
2. Per volledige disjuncte request-coverage: `sum(cluster.locationCount) + numberOfIndividualLocationItems == coverage.locationCount`. In het illustratieve voorbeeld hierboven is dat **100 + 1 = 101**.
3. Alle items zijn berekend uit dezelfde filterset en publicatieversie; dezelfde locatie hoort bij precies één volledig uitgerenderde cel. Geen directe onzichtbare ruwe LIMIT.
4. `complete = true` is nooit toegestaan bij timeout, gedeeltelijke SQL-resultaten, onbekende relevante cel of afgebroken antwoord. HTTP-success moet contractueel onderscheiden blijven van volledige datasetdekking.
5. De ring gebruikt de exacte tellerfracties: groen `free/locationCount`, rood `noFree/locationCount`, onbekend `unknown/locationCount`. Android mag niet eerst afronden en daardoor segmenten missen.
6. Bij geaggregeerde tellingen is stale feed **geen** automatische onbekend-status; betrouwbare laatste EVSE-counts mogen zichtbaar blijven met passende bronwaarschuwing.
7. `bounds`, `cellIds` en `gridLevel` moeten in hetzelfde gridstelsel staan. Cache en markerinteractie mogen niet op afgeronde UI-coördinaten vertrouwen.
8. `publishedVersion` mag alleen bestaan op basis van aantoonbare consistente dataversie. Als de bestaande importer geen atomair identificeerbare versie publiceert, is eerst een expliciet versie-/transactioneel-consistentieplan nodig; gebruik niet zomaar de laatste `snapshot_poll_checkpoint` als bewijs van complete datamutatie.

### HTTP-fout- en cachegedrag (nog te toetsen)

- `400`: ongeldige bbox, zoom of filterparameters. Geen impliciete versoepeling van filters.
- `429` / `503`: begrensde serverbelasting of tijdelijk niet beschikbaar. Android toont indien mogelijk eerder correcte cachedekking met duidelijke ouderdom, zonder dit als verse volledige dekking te etiketteren.
- Een legitiem lege gefilterde coverage is `200`, `complete=true`, `locationCount=0`, `items=[]`.
- Een objectbudgetoverschrijding mag niet worden opgelost met stilzwijgend `items.take(N)`; de server moet de resolutie verruimen of eerlijk falen. Per-request/per-tile guards, browser-/launcher-caching en rate limiting zijn nodig.
- Onderzoek `ETag`/`Cache-Control` en `304` **alleen** als de catalog-/filter-/gridversie aantoonbaar consistent is. Posities veranderen relatief zelden; beschikbaarheidsringen vereisen een versheidsplan.
- Bij een HTTP `200` met `complete=false` mag de Android-parser het nooit als volledig presenteren; bespreek of het nieuwe endpoint simpelweg **alleen complete successresponses** toestaat, zoals de huidige kaartclient feitelijk eist. Dit heeft voorkeur zolang er geen helder partial-tileprotocol bestaat.
- Een oudere APK moet nog altijd naar de bestaande `/api/v1/map/viewport` kunnen blijven praten totdat de nieuwe route expliciet is uitgerold.

### Validaties voor eerste contract-PR (pas na goedkeuring)

- Contractfixture met **100** locaties: 70 groen, 15 rood, 15 onbekend → exact 70/15/15 ring; `coverage.locationCount=100`.
- Mixfixture met cluster van 3 (1 groen, 1 rood, 1 onbekend) en twee losse locaties → `coverage.locationCount=5`.
- Filterfixture met connector-, AC/DC-, vermogens- en `minFree`-combinaties: dezelfde individuele locaties in nieuw en bestaand endpoint na volledig inzoomen.
- Randen: lege coverage, exact samenvallende coördinaten, grens tussen twee cellen/tiles, buffer, integer-overflow, snelle pan/filterwijziging, stale feed, unknown versus nul, 999+ en landelijk overzicht.
- Performancebenchmarks: cold/warm SQL, HTTP p50/p95, bytes, aantal getekende markers en Android-frametijd; geen getallen of extra indexen kiezen vóór metingen.

**Nog expliciet te beslissen:** minimale individuele markerpayload versus eerste-frame sheet, keuze voor canonical cells/tiles, foutcontract bij gedeeltelijke coverage, daadwerkelijk betrouwbare publicatieversie, cachebeleid en toekomstige NDW-fallback op landelijke clusters.

---

## 4. Datacontract dat we moeten bewaken

Per bekende laadlocatie moet expliciet worden vastgesteld of en waar we deze velden nodig hebben:
- Publieke `featureId`, intern ID alleen indien noodzakelijk, actieve/inactieve/niet-gevonden-status, naam/adres/plaats/operator;
- `availableCount`, `totalCount`, betrouwbaarheid/`?`, niet-operationele EVSE-uitsluitingen (zoals `PLANNED`, `REMOVED`), deduplicatie op EVSE en expliciet/berekend connectorvermogen;
- `sourceUpdatedAt` (bronwijzigingsmoment) los van `feedLastObservedAt` en `feedIsStale` (nationale feedwaarneming), plus client-fetchmoment voor `Laatst bekend`;
- Connectorcategorieën en actueel vermogen alleen waar getoond, tarieven/voorwaarden alleen waar getoond, statistieken alleen waar nodig;
- Ontbrekende ID, ambigue NDW-match, onbetrouwbare telling, partiële bulkresponse, fallback op NDW en offline-/cachepad;
- Bounds en validatie voor batchgrootte, querylengte, duplicaten, volgorde, statuscode, responseversie, eventuele `ETag` en per-item fouten.

**Serversemantiek mag niet divergeren:** hergebruik zoveel mogelijk bestaande query-/aggregatielogica uit `ApiDatabase.GetMapViewport()` en detail-API; maak geen tweede berekening van beschikbaarheid die afwijkende aantallen oplevert. De kaart-API telt `AVAILABLE` en sluit `PLANNED`/`REMOVED` uit van capaciteit, volgens huidige code. Controleer actief/inactief en afwijkende bronstatussen bij contractontwerp.

## 5. Slimme Android-aanvragen: gewenste volgorde

1. **Kaart laden:** viewport-API (filters, NDW-fallback zoals nu) voedt markers en de onmiddellijk zichtbare basis van de detailsheet.
2. **Tik op marker:** sheet toont direct bestaand `MapLocation`. Bepaal op basis van actualiteit en dekking of een gerichte beschikbaarheid/samenvattingsrefresh zinvol is (niet blind elke tik).
3. **Compacte sheet:** tarieven/voorwaarden alleen ophalen wanneer nodig voor die compacte UX, met onafhankelijke loading; geen statistiekenaanroep als de gebruiker niet uitbreidt.
4. **Uitgebreide sheet:** één keer aanvullende details/statistieken per geselecteerde locatie opvragen, cache binnen redelijke TTL; verzoeken van oude selectie annuleren/ignoren.
5. **Favorieten/widget:** geselecteerde `featureId`-set in één passende read-only request; lokale alias/volgorde en betrouwbare lokale cache blijven leidend voor de presentatie; direct update van snelle resultaten.
6. **Offline/onbereikbaar:** geen `0` verzinnen, oude ratio expliciet als `Laatst bekend`, overige items `?`; NDW-fallback alleen wanneer betrouwbaar en contractueel passend.

**Let op:** dit is nog geen overeengekomen interactiecontract. Vooral het refreshmoment op markerselectie, de noodzaak van tarieven in compact en de statistiek-lazy-trigger vragen eerst UX-afstemming.

## 6. Meten vóór implementatie

Beperk benchmarks tot representatieve, herhaalbare meetgevallen op een testomgeving:
- 1 / 2 / 5 favorieten, dichtbij elkaar én Oss/Elst-achtig verspreid;
- Cold/warm cache, kaart al in geheugen of niet, een ontbrekende/inactieve/ambigue ID;
- API gezond, API traag, API fout, NDW-fallback, onvolledige feedobservatie;
- Tap compacte sheet; volledig uitbreiden; tik tussen verschillende markers; refresh widget;
- Meet requestaantal, `p50/p95`, bytes, `EXPLAIN ANALYZE`/query- en indexduur, tijd tot eerste betrouwbare ratio, moment waarop statistieken klaar zijn en minimale dataoverdracht.

**Nog niet gemeten:** geen geloofwaardige milliseconde-doelen of database-indexclaims vastleggen zonder deze baselines. Compareer A/B/C en leg gekozen grenzen voor aan gebruiker vóór implementatie.

## 6A. Reproduceerbaar read-only benchmarkprotocol voor kaart v2 (2 november 2026)

**Status:** voorbereide meetprocedure; **niet uitgevoerd**. Dit is een voorstel voor een gecontroleerde benchmark nadat gebruiker expliciet toestemming voor NAS-metingen heeft gegeven. Geen productiegegevens muteren, geen migraties en geen schemamodificaties. Voer pas na bespreking op de werkelijke NAS uit met heldere belastinggrenzen.

### Veiligheid en testomgeving

- **Voorkeur:** geïsoleerde kopie van representatieve NDW-catalogus, met alleen-lezen databaseaccount en dezelfde PostgreSQL/PostGIS-versie/indexen als productie. Is dat niet mogelijk, start klein op productie en plan landelijke queries expliciet; geen onbegrensde parallelle tests.
- **Voor iedere sessie:** leg `SELECT current_database(), current_user(), current_setting('server_version'), current_setting('transaction_read_only')` vast en bevestig dat de verbinding met de verwachte **read-only rol** draait. Gebruik één sessie voor de test en limieten. Verifieer werkelijke schema/indexen via `pg_indexes`/catalogusweergaven; init-schema alleen is niet voldoende bewijs.
- **Transactie:** `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;` voor consistente meervoudige metingen, tenzij de gewenste werkelijke-API-statementisolatie apart wordt onderzocht. `SET LOCAL statement_timeout = '5s'` en `SET LOCAL lock_timeout = '1s'` als voorlopige conservatieve bovengrenzen, vóór de eerste query; stel runtime-/resourcebudget bij op basis van de meting. `ROLLBACK` altijd.
- **Geen `EXPLAIN ANALYZE` van muterende statements**; alleen `SELECT`. `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` voert de SELECT werkelijk uit; ook een read-only query kan duur zijn: eerst `EXPLAIN (FORMAT JSON)` zonder uitvoeren en kleine regio. Gebruik niet automatisch `ANALYZE`/tijdrovende landelijke combinaties.
- Voorkom een onbedoeld langdurige transactiesnapshot (vacuum-impact). Meet in korte afzonderlijke sessies/sets en sluit ze direct. Bij timeouts, hoog NAS-CPU/IO of verhoogde API-latency: **stop** en verklein de testcase.

### Testmatrix

| Case | Geografie / filters | Waarom |
|---|---|---|
| B1 | Klein straatgebied Oss, geen filters | Basis-latency bij losse punten |
| B2 | Gemeente/regio Oss–Elst, geen filters | Mix van individuele locaties en clusters |
| B3 | Dicht stedelijk gebied (bijv. centrum Utrecht), geen filters | Hoge locatiedichtheid |
| B4 | Regionaal overzicht Noord-Brabant, geen filters | Cluster/zoom en meer locatiebezoeken |
| B5 | Heel Nederland, geen filters | Landelijke dekking zonder 999-limiet |
| B6 | B3/B4 met `connectorType=ccs2`, `mode=dc`, `power=gte150` | Technische EXISTS-filters |
| B7 | B3/B4 met `minFree=1` en gecombineerde technische filters | Statusafhankelijke filtersemantiek |
| B8 | B4/B5 met representatieve operatorfilter(s) | Selectieve operatorpredicaat |
| B9 | Lege filteruitkomst of deels onbekende statussen | Correctheid zonder verzonnen nul |
| B10 | Grenscellen/gelijke coördinaten en pan over celgrens | Geen duplicatie of ontbrekende locaties |

**Opmerking:** de bestaande `/map/viewport` mag bij grote gebieden terecht HTTP 413 geven; dat is een vastgestelde baselinebeperking, geen vereiste om met verhoogde LIMIT toch alle individuele records op te halen. Meet de landelijke vergelijking uitsluitend op een nog te ontwerpen **geaggregeerd SELECT-plan**, niet door de bestaande API onbegrenst te maken.

### Variants & meetvelden

**A0 — bestaand:** huidige `GetMapViewport` + filtergedrag op een klein gebied; behoud huidige 999-cap, en meet aantal records/bytes en Android-clustering.  
**A1 — nieuwe live grid-query:** filterlocaties → één betrouwbare availabilitysamenvatting per locatie uit actieve EVSE's → vaste Web Mercator-cellen → `locationsWithFree/NoFree/Unknown` en singleton-`featureId` → complete coverage zonder ruwe LIMIT. Een SQL-prototype mag alleen als zelfstandige **read-only SELECT** draaien.  
**B — mogelijke locatiesummary:** *nog niet bestaand*. Eerlijke A/B-benchmark vereist eerst een expliciet goedgekeurde **geïsoleerde proefdatabase** met representatieve `location_availability_summary`-rijen; **geen tijdelijke of permanente tabellen op productie creëren** onder het label read-only. Zonder die testomgeving is B slechts een theoretische querykostenraming.  
**C — vooraf berekende grid-/tilecache:** alleen simuleren met in-memory/HTTP-resultaatcache indien technisch beschikbaar, anders als analysepunt; geen nieuwe productietabellen of warmingsjobs.

Leg per case en variant vast: omvang geïnspecteerde records, werkelijke `matchedLocationCount`, aantal resultaatobjecten en gridLevel, `executionTimeMs`, `shared_hit/read`, planningtijd, totale HTTP-bytes, API-p50/p95, Android-laad-/renderduur, fout-/timeoutpercentage, cachehitratio en de NDW-feed-/publicatiemetadata. Rapporteer medianen bij herhalingen en afzonderlijk cold/warm-cachegedrag; doe **geen** claims over schijfcold-cache door een gewone tweede query.

### Correctheidschecks vóór snelheid

- Controleer iedere objectset op `sum(cluster.locationCount) + count(singletons) == matchedLocationCount`; tel buffer-/overlapcellen slechts één keer.
- `locationsWithFree + locationsWithNoFree + locationsUnknown == locationCount` voor iedere cluster; nul totale EVSE-capaciteit of onbetrouwbare source = unknown, nooit automatisch rood.
- Filter-/statuspariteit met de bestaande locatie-/kaart-API in volledig ingezoomde controlegebieden.
- Identificatie van exacte coördinaatdubbelen, stabiele celtoewijzing, grenzen en kleine pans; in-flight API-responses mogen geen filters/datasetversie verwisselen.
- Een geslaagde import tijdens read-only tests mag de consistency-invariant niet breken; controleer of één statement of REPEATABLE READ nodig is, en dat de publicatieversie daadwerkelijk bestaat.

### Besliscriteria na uitvoering

1. **A1 voldoende snel en stabiel:** kies live server-rasterclustering zonder nieuwe DB-tabel; ontwerp daarna de kaart-API en Android-kaartclient gericht.
2. **A1 DB-CPU/IO of p95 te zwaar:** benchmark B op een *geïsoleerde* database met transactionele status-summary; toets extra importschrijftijd versus netwerk-/querywinst.
3. **Herhaald landelijke overview onverantwoord, ondanks B:** onderzoek cachebare vaste tiles/vooraggregatie of vector tiles, met vooral aandacht voor dynamische filters.
4. Ongeacht de snelste variant mag geen enkele optie het NDW-/filter-/onbekendcontract, de optelsommen of volledige landelijke dekking opofferen.

**Rapportageformat:** geef per testcase dataset-/filter-/zoomcontext, geplande queryvorm, daadwerkelijk uitgevoerde tool/SQL, aantal herhalingen, tijd en resources, matchtellingen, fouten, en één korte conclusie. Schrijf `niet gemeten` waar geen uitvoer is. **Geen NAS-test of implementatie starten zonder expliciet akkoord en passende omstandigheden.**

---

### Concrete SQL-proefquery A1 — gereed voor review (2 november 2026)

De eerste, **niet uitgevoerde**, ongefilterde PostGIS-grid-`SELECT` staat in [MAP_GRID_A1_SQL_PROBE.md](MAP_GRID_A1_SQL_PROBE.md). Deze gebruikt de bestaande GiST-locatieindex en actieve EVSE-relatie, een vaste Mercator-cell-ID, één statusaggregatie per locatie en vervolgens `locationsWithFree/NoFree/Unknown` per gridcel. Hij bevat expliciete read-only transactie en conservatieve timeouts. Dit is een **klein Oss-proefgebied**, niet de daadwerkelijke volledige-cel- of landelijke API-implementatie; filters, bronbetrouwbaarheid en gridboundary-consistentie moeten vóór productie verder gevalideerd worden. Geen echte SQL-executie, benchmark of NAS-toegang uitgevoerd.

## 7. Fasering en beslismomenten

**Fase 0 — ontwerp:** exacte veld-/requestmatrix, identiteits-/broncontract, querykosten, alternatieven, UX voor compact/expanded en favorieten-widget, risico's. Gebruiker beoordeelt en kiest architectuurrichting. Geen code of deployment.

**Fase 1 — API (alleen na akkoord):** klein backward-compatible read-only contract, gerichte SQL-/contract-/databetrouwbaarheidstests, PR. Voor NAS-uitrol apart toestemming vragen.

**Fase 2 — Android (alleen na akkoord):** gedeelde lichte client, caching, lazy details/statistieken, widget/favorietenaanpassing. Zorg voor overgangsfallback zolang NAS nog oude versie heeft. Fysieke Samsung-acceptatie. Op afzonderlijke PR('s) en merge-/deployakkoord wachten.

**Featuregrens:** bestaande widget-PR #112 niet nu samenvoegen, rebasen of deployen. Het is een zelfstandige UX/feature met tijdelijke viewport-oplossing; #113 bepaalt pas na gezamenlijk ontwerp welke daarvan structureel vervalt.

## 8. Eerste ontwerpbesluiten om samen te nemen

1. Willen we bij tikken op een marker **altijd** een actuele gerichte statusrefresh, of alleen bij onvoldoende verse kaartgegevens?
2. Moet de **compacte sheet actuele tariefgegevens** blijven tonen? Zo ja, wel de tariefdetails ophalen maar **statistieken lazy**?
3. Hoeveel locaties moet een bulkendpoint ondersteunen: alleen 1–5 widgetfavorieten of ook langere favorietenlijsten?
4. Welke cacheduur geldt voor beschikbaarheid, connectoren, tarieven en statistieken afzonderlijk?
5. Welke latency en foutgevallen zijn voor acceptatie doorslaggevend?

Dit zijn **open ontwerpvragen**, geen blokkade voor het onderzoek. Gebruik de eerste gezamenlijke review om de gekozen volgorde vast te leggen.
