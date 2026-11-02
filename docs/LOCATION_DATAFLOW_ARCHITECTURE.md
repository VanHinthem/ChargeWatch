# ChargeWatch — locatie-dataflow en gerichte opvraagstrategie (#113)

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

### Clusteringopties en afweging

| Optie | Gedrag | Plus | Onderzoek/risico |
|---|---|---|---|
| A. Dynamische **server-side rasterclustering** o.b.v. bbox + zoom | SQL/PostGIS levert zichtbare clusters én losse locaties | Klein payloadbudget, eigen endpointcontract en geleidelijke migratie | Gridstabiliteit bij pannen/zoom, randcellen/dubbeltellingen, DB CPU bij veel concurrente requests |
| B. Vooraf geaggregeerde zoom-/gridlagen | Materialisatie/caching per zoomgebied en bronstatus | Snel voor landelijke overview | Filters maken vooraf aggregaties moeilijk; verversing/feed-actualiteit en opslag |
| C. **Vector tiles/MVT** en MapLibre-clusterlayers | Ruimtelijke tiles naar client, markertekening GPU-vriendelijk | Potentieel schaalbaar en cachebaar | Grote Android/UI-refactor, interactie/clicktargets/labels, filtervarianten, datakwaliteit |
| D. Meer locaties ophalen, Android-clustering behouden | Huidige API-limiet verhogen | Kleine eerste wijziging | Grote payloads, CPU/GC en rendering blijven bottleneck; voldoet niet goed aan landelijke dekking |

**Werkhypothese voor de benchmark:** eerst A onderzoeken en afzetten tegen C, zonder de beslissing al vast te leggen. Beheerbare kaartobjecten als **budget** is iets anders dan onderliggende locaties trunceren. Als een API-bestek niet kan worden gehaald, mag hij **niet** stilzwijgend relevante delen van Nederland laten wegvallen: zoomniveau/aggregatie verhogen, duidelijk statuscontract of gecontroleerde fout, te valideren.

### UX- en databetrouwbaarheidspunten die nog uitgewerkt moeten worden

- **Zoom & pan:** landelijke begin-/uitzoomkaart mogelijk; clusters vallen steeds verder uiteen tot individuele locaties. Stabiele clusterposities over pan-/zoomovergangen, geen markerflits of onverwachte lege regio's.
- **Clustertik:** bij lager zoomniveau gericht inzoomen op clusterregio; pas een enkele locatie opent de bestaande compacte sheet. Bij maximumzoom en meerdere exact samenliggende locaties moet een expliciete selectie mogelijk blijven (niet eindeloos inzoomen).
- **Favorieten:** de server kent geen lokale favorieten. Overweeg client-overlay van lokale favorietmarkeringen, voorkom dubbele pins bovenop clusters en pas kleur/sterregels consistent toe. Geen alias of lijst naar server versturen.
- **Filter-pariteit:** clusterlocaties én aantallen komen voort uit exact dezelfde gefilterde dataset als een volledig ingezoomde kaart; voorwaarden voor `minFree` en connectorfilters niet verwarren met de in de badge getoonde *volledige* EVSE-ratio. Onduidelijke filtersemantiek expliciet testen.
- **NDW-fallback:** directe NDW GeoJSON biedt niet vanzelf hetzelfde server-geaggregeerde contract. Ontwerp een zichtbaar degradatiepad zonder stilzwijgend ongefilterde/partiële clusters te tonen.
- **Minimale kaartdata versus direct bruikbare sheet:** een lightweight object kan slechts coords, featureId, aantallen, kwaliteit en bronversheid dragen. Bepaal de minimale *gewenste sheet-first-frame*, en of beperkte naam/adresinformatie eventueel in een cache mee moet om plotselinge lege compacte sheets te voorkomen.
- **Rekenkosten:** vermijd per pan opnieuw alle EVSE/connector-joins over de hele Nederlandse catalogus. Onderzoek index- en queryplannen, cachingstrategie, rastercelresolutie, huidige `MapFilter`-predicate en eventuele materialisatie; geen nieuwe index/schema zonder meetresultaten.

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
