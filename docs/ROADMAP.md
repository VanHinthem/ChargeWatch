# ChargeWatch — Roadmap en onderzoeksagenda

## Android-sheetstatistieken on-demand (4 november 2026)

De compacte kaart-sheet vraagt voortaan alleen locatiedetails en tarieven via `by-ndw-id/{featureId}` op. De aparte `/statistics`-aanroep wordt pas gestart wanneer de sheet daadwerkelijk wordt uitgebreid en wordt binnen dezelfde sheet per locatie onthouden. Het favorietenscherm opent de gedeelde sheet al direct volledig, zodat historie daar gewoon meteen wordt geladen. De favorietenlijst blijft beschikbaarheid verversen via de bestaande viewport-API met maximaal twee minuten fetchcache; aan die datastroom is niets gewijzigd. [Technisch verslag](SHEET_STATISTICS_ON_DEMAND_ANDROID.md). **Android-only, eerst draft-PR en CI; geen merge/deployment zonder afzonderlijk akkoord.**

---


## #113 — Favorietster op clusters (4 november 2026)

De Samsung-acceptatie na PR #121 liet zien dat de clusterpresentatie nu consistent is, maar dat de gele favorietster op clusters ontbreekt. Fix op `fix/map-cluster-favorite-badge-113`: lokale groepen krijgen de ster via exacte `featureId`-membership, landelijke groepen via opgeslagen lokale coördinaten en canonieke celmembership (zonder actieve filters), met één identieke sterbadge en correct cachegedrag. Bij gefilterde serveraggregaten wordt geen onbewezen ster getoond. [Ontwerp, privacygrens en testmatrix](MAP_CLUSTER_FAVORITES_113.md). Geen API/NAS-wijziging; PR blijft eerst draft, geen merge/deploy zonder akkoord.

---


## #113 Android-acceptatie: clustersemantiek over beide kaartmodi gelijkgetrokken (4 november 2026)

Na PR #120 is de rasterindeling visueel verdwenen, maar op de Samsung verschilt clustersemantiek nog: een regionale kaart toont locatieaantallen met statusring (bijv. `419`), terwijl de lokale kaart `vrij/totaal`-somlabels in een monochrome groene/rode cluster toont (bijv. `31/46`). De correctie op `fix/map-cluster-consistent-presentation-113` voert één pixel-gebaseerde clusterengine en één groen/rood/onbekend-ring voor **alle** clusters in, met centraal het **aantal laadlocaties**; uitsluitend losse locaties tonen `vrij/totaal`. Beide clustertappaden delen dezelfde zoom-/selectielogica. [Technisch en UX-contract](MAP_CLUSTER_CONSISTENCY_113.md). Nieuw werk blijft ter review; **geen merge/deployment zonder afzonderlijk akkoord**.

---


## #113 Samsung-acceptatie: rastervormige clustering afgewezen (4 november 2026)

De landelijke overview in `main` na PR #119 bleek bij Oss zichtbare, regelmatig verdeelde vierkante rasteraggregaten te tonen (bijv. na aantikken van 19 locaties opnieuw 4/6/3/6/2/4). Dit is nadrukkelijk **niet** de gewenste kaartinteractie. Correctie op `fix/map-overview-113-street-level-clustering`: overview gebruikt het daadwerkelijke locatiegewogen centroid en beeldscherm-gebaseerde visuele clustering, terwijl de volledige bestaande viewport met individuele `vrij/totaal`-markers gebruikt wordt in kleine gebieden. Alleen bij de 999-locatiegrens blijft het volledige overview-aggregaat nodig. [Ontwerp/testplan](MAP_OVERVIEW_113_NATURAL_CLUSTERING_FIX.md). Niet gemerged/gedeployed; bestaande NAS draait nog fase D.

---


## #113 fase D — Android-integratie in draft PR #119 (4 november 2026)

Na de merge van PR #118 (`6d5c320`) wordt de bestaande MapLibre-kaart op een afzonderlijke featurebranch uitgebreid met een optionele landelijke overviewmodus, serverclusters met groen/rood/onbekend-ring, een paginated read-only rastercelroute voor locatiekeuze en een tijdelijke kaartmoduskeuze in Instellingen. **De standaard blijft de bestaande kaart** totdat de nieuwe API afzonderlijk mag worden gedeployed en geactiveerd. [Fase-D-ontwerp en implementatie](MAP_OVERVIEW_113_PHASE_D_ANDROID.md) · [Draft PR #119](https://github.com/VanHinthem/ChargeWatch/pull/119). Nog vereist: groene CI, fysieke Android-acceptatie, geautoriseerde NAS-HTTP/meting, expliciete merge- en deploymentbesluiten.


**Status (30 oktober 2026):** De landelijke databasis, read-only API en de belangrijkste Android-kaart-/zoek-/filterfuncties zijn al geïmplementeerd. De onderstaande oorspronkelijke fasechecklists bevatten historische, niet volledig bijgewerkte `[ ]`-markeringen en gelden niet als live implementatiestatus. De actuele afgeronde werkzaamheden staan in [STATUS.md](STATUS.md); de vastgestelde **resterende Android-v1-volgorde** staat hieronder.

## #113 fase C — adaptief landelijke overview in draft PR #117 (4 november 2026)

De backend-overview kiest één conservatief canoniek Web Mercator-gridlevel per request met maximaal 500 markerobjecten en volledige celdekking, zonder locatie-LIMIT; filter- en beschikbaarheidsregels blijven gelijk. **Fase C is naar `main` gemerged via PR #117** (4 november 2026). Geautomatiseerde echte HTTP/Npgsql/PostGIS-tests zijn groen, maar **er is geen NAS-deployment en de productiefeatureflag blijft uit**. **NAS-read-only SQL-validatie PASS op 4 november 2026:** 83.408 locaties in 13 markers (1.464,780 ms), CCS2/DC 1.612 in 84 markers (164,856 ms), Oss 489 in 116 markers (13,035 ms); alle status-/budgetbewaking PASS. Dit zijn losse SQL-tijden, geen productie-HTTP-p95. [Meetrapport](MAP_OVERVIEW_113_NAS_READONLY_BATCH.md). Nu: gecontroleerde HTTP-/featureflag-gate en daarna Android-integratie, zonder historisch onderzoek te herhalen. [Detailstatus](MAP_OVERVIEW_113_PHASE_C_IMPLEMENTED.md).

## Besluit kaartarchitectuur — landelijke dekking op ieder zoomniveau (2 november 2026)

Binnen [#113](https://github.com/VanHinthem/ChargeWatch/issues/113) krijgt de **kaartlaag als eerste prioriteit**. De gebruiker heeft expliciet gekozen dat ChargeWatch **heel Nederland** moet kunnen tonen, ook volledig uitgezoomd: grote clusters bij lage zoom, clusters en losse laadlocaties gemengd bij tussenzoom en individuele locaties bij voldoende hoge zoom. Daarbij blijft filtering vóór aggregatie betrouwbaar en worden nooit locaties stilzwijgend weggelaten om onder een markerlimiet te blijven. De huidige 999-locatieblokkade is niet het gewenste gedrag van het nieuwe overzichtscontract.

**Nog uit te werken, niet besloten:** minimale kaartpayload versus direct te vullen compacte sheet, server-side rasteraggregatie versus vector tiles, databetrouwbaarheid/EVSE-counting per cluster, clustertik, zichtbare lokale favorieten, georuimtelijke indexen, caching en performancebudget. Geen harde zoomgrenzen of aantallen te tekenen markers kiezen zonder benchmark. Zie [LOCATION_DATAFLOW_ARCHITECTURE.md](LOCATION_DATAFLOW_ARCHITECTURE.md) op draft-PR #114. Geen implementatie-/merge-/NAS-deployakkoord.

---

## Nieuwe architectuurprioriteit — 2 november 2026: locatie-dataflow #113

De gebruiker heeft na het onderzoeken van de huidige **kaart → sheet → favorieten/widget**-aanroepen gevraagd hiervoor een **aparte feature** te maken en de volledige data-ophaalstrategie eerst zorgvuldig uit te werken en te optimaliseren. [Issue #113](https://github.com/VanHinthem/ChargeWatch/issues/113) en [LOCATION_DATAFLOW_ARCHITECTURE.md](LOCATION_DATAFLOW_ARCHITECTURE.md) vormen de ontwerp-/meetagenda.

**Planning:** onderzoek en ontwerp van #113 krijgen nu de aandacht **vóór verdere architectuurveranderingen aan de widget**, zonder #101/#112 samen te voegen of de al gebouwde widget als afgerond te verklaren. Eerst gegevens-/ID-contract, API-last, lazy sheetverrijking, bulkalternatieven en performance-baselines bespreken; **pas na een afzonderlijk ontwerp-/implementatieakkoord code schrijven**. De onderstaande P1–P4-volgorde was het eerdere v1-plan van 30 oktober en blijft historisch; dit nieuwe besluit voegt een gerichte architectuurverbetering toe, geen onbeperkte nieuwe roadmapfase.

**Niet besloten:** een bulkavailability-endpoint of nieuwe DB-index is nog geen vaststaand contract. Inzicht in bestaand direct-ID-detail, bronversheid, operationele EVSE-counting en NDW-fallback gaat vóór keuze. Geen backenduitrol/NAS-deploy of merge zonder expliciet akkoord.

---

## Vastgestelde volgorde Android-vervolgwerk — 30 oktober 2026

De gebruiker heeft na afronding en Samsung-acceptatie van [issue #95](https://github.com/VanHinthem/ChargeWatch/issues/95) de volgende **strikte prioriteitsvolgorde** gekozen. Werk de stappen in deze volgorde af; vervolgissues kunnen vóór de uitvoering worden verfijnd, maar een wijziging van prioriteit vereist een nieuw productbesluit.

1. **[P1 — GPS / huidige locatie en nabije alternatieven (#99)](https://github.com/VanHinthem/ChargeWatch/issues/99).** Toestelpositie met expliciete permissie en bruikbare fallback; vanaf een laadlocatie geschikte alternatieven in de buurt vinden. Bestaande PDOK-pin, vijf-nearest en filtering behouden. **UX vastgesteld op 2 november 2026; Android-uitwerking in PR #109. Zie [GPS_NEARBY_ALTERNATIVES.md](GPS_NEARBY_ALTERNATIVES.md).**
2. **[P2 — Native Android-widget met lokale favorieten (#101)](https://github.com/VanHinthem/ChargeWatch/issues/101).** Compacte en uitgebreidere widgets, configuratie met favoriete locaties, betrouwbare beschikbaarheid, bronversheid en Android-conforme refresh.
3. **[P3 — Statistieken, 90-daagse uptime en publicatiekwaliteit (#103)](https://github.com/VanHinthem/ChargeWatch/issues/103).** Eerst datakwaliteits-/API-publicatiecriteria valideren; daarna historische weekdag-/uurweergave en EVSE-betrouwbaarheid afronden. Geen percentages op basis van experimentele of ongeldige data.
4. **[P4 — Android-kwaliteitsronde en releasevoorbereiding (#104)](https://github.com/VanHinthem/ChargeWatch/issues/104).** Integrale Samsung-/offline-/toegankelijkheid-/performance-/release- en securitycheck. Geen release of NAS-deploy zonder afzonderlijk akkoord.

**Buiten deze vier stappen:** [#79](https://github.com/VanHinthem/ChargeWatch/issues/79) is een **uitgestelde** roadmaptaak voor een uitgebreider datakwaliteitsdashboard, geen voorwaarde om P1 te starten. De vroegere Android-kaartfilters en PDOK-bestemmingzoekfunctie (#95, PR #97) zijn afgerond. Deze volgordewijziging is een **planningbesluit**, geen implementatie- of deploymentautorisatie.



## Fase 0 — Datafundament (eerste stap)

- [ ] Landelijk OCPI Locations gzip-bestand downloaden en de echte JSON-structuur inspecteren.
- [ ] Schietspoel 18 terugvinden en individuele EVSE-statussen vergelijken met de bestaande Athlon-JSON.
- [ ] OCPI Tariffs-download parsen en de getoonde tariff-ID aan daadwerkelijke componenten koppelen.
- [ ] Samengestelde identificatie (OCPI country/party/location) en updategedrag testen.
- [ ] Updatefrequentie, bestandsgrootte, geheugenverbruik en conditionele HTTP-requests meten.
- [ ] NDW-licentie, attributie en documentatie/API-stabiliteit vastleggen.
- [ ] Proefberekening en PostgreSQL-proefimport voor landelijke catalogus, **EVSE- én locatieprofielen (168 vakken per entiteit)** en 90-daagse uptime-dagtotalen; werkelijke tabel-/indexgrootte meten.
- [ ] Statistiekvalidatie: versheid, geldige statusmapping, tijdgewogen uurmeting, ontbrekende data en rechtstreeks gemeten locatiebeschikbaarheid.
- [ ] Onderzoek de waarneembaarheid van AVAILABLE-, niet-vrije en storingsperioden inclusief ontbrekende start/eindpunten; kies compacte duur-buckets en meet opslagoverhead.
- [ ] Onderzoek **locatiebrede perioden met 0 vrije EVSE's** en herstel naar >=1 vrij, inclusief storings-/blokkadesemantiek, censurering en opslagmeting.
- [ ] Definitieve importcontracten, technisch datamodel en API-schema opstellen.

**Exit criteria:** succesvolle reproduceerbare ingest, betrouwbare statusinterpretatie, inzicht in datasetlimieten en akkoord op dataschema.

## Fase 1 — Landelijke database en read-only API (versie 1 fundament)

- [ ] ASP.NET Core .NET 10 solution, importer en Docker Compose op NAS.
- [ ] PostgreSQL + PostGIS: aparte schrijvende importrol en **uitsluitend lezende API-rol**.
- [ ] Betrouwbare, transactionele landelijke OCPI Locations-/Tariffs-import met staging, validatie en monitoring.
- [ ] Actuele locatie-, EVSE-, connector- en tariefdata; geen tariefhistorie.
- [ ] Vanaf de eerste import landelijke 168-vaks EWMA-profielen **per EVSE én per locatie**, en 90-daagse EVSE-uptime-dagtotalen.
- [ ] Per EVSE compacte duurverdelingen en tellers verzamelen voor **bezettings-/niet-vrije perioden, vrije perioden, storingsfrequentie, storingsduur en statusovergangen**, plus dekking/kwaliteit; geen permanente eventhistorie.
- [ ] **Per locatie** compacte verdeling van aaneengesloten perioden met **nul vrije aansluitingen**, moment van herstel naar >=1 vrij, aantallen en dekking direct uit gecombineerde statussen bijhouden; storingen niet als normale bezetting presenteren.
- [ ] Compacte arrays versus tabelrijen meten, benchmarken en opslag/indexen optimaliseren.
- [ ] REST API met uitsluitend **GET-endpoints** voor kaart, zoeken, filtering, details, status, huidige tarieven, alternatieven, **EVSE-/locatiestatistieken, uptime en historische beschikbaarheidskansen**.
- [ ] Publicatiecriteria voor statistieken: voldoende observaties/dekking, geldige bronstatus, tijdvak en actualiteit, anders 'onvoldoende gegevens'.
- [ ] Tests, dataversheid, performance en CI.

## Fase 2 — Android-app en widget (release 1.0)

- [ ] Kotlin + Jetpack Compose: kaart, zoeken, filters, detailpagina en actuele tarieven.
- [ ] **Favorieten uitsluitend lokaal** opslaan (Room/DataStore), inclusief naam en volgorde.
- [ ] Native widget: 2–5 favorieten, beschikbaarheid, dataversheid, verversen.
- [ ] Nabije alternatieven en navigatie via bestaande kaartenapp.
- [ ] **Statistieken in v1:** bezettingsprofiel per EVSE en per locatie, bijvoorbeeld weekdag-/uur-heatmap.
- [ ] **Betrouwbaarheid in v1:** 90-daagse uptime per EVSE met meetdekking.
- [ ] **Historische beschikbaarheidskans in v1:** per gekozen weekdag/uur de rechtstreeks gemeten EWMA-kans op >=1 vrije EVSE, met indicatie van datakwaliteit; geen voorspelling van het exacte vrijkom-moment.
- [ ] Verouderde/onbekende brondata helder tonen; offline-cache is expliciet 'laatst bekend'.
- [ ] Test op fysieke Android-telefoon en NAS-deployment.

**V1 exit criteria:** Schietspoel 18 vinden, individuele statussen en actuele tariefinformatie zien, lokaal favoriet maken, widget tonen, EVSE-/locatiestatistieken en uptime bekijken, een weekdag/uur-kansinschatting opvragen en alternatieven vinden. Onvoldoende data wordt expliciet als zodanig getoond. De API heeft geen write-endpoints of server-side persoonlijke opslag. Databasegrootte en importduur zijn daadwerkelijk gemeten.

## Fase 3 — Statistiekverfijning na v1

- [ ] α/EWMA-kwaliteit, dekking en kalibratie evalueren met langere echte meetreeksen.
- [ ] Praktische rangschikking van alternatieven verder verbeteren.
- [ ] Uitgebreidere analyses onderzoeken zonder de opslag onbegrensd te laten groeien.

## Fase 4 — Uitgebreide tarieven en geavanceerde voorspellingen

- [ ] Actuele OCPI-tarieven nauwkeurig interpreteren, met prijscomponenten en contractonzekerheid.
- [ ] Kostencalculator per laadsessie, zonder historie van tarieven.
- [ ] Historische beschikbaarheidskansen uit v1 backtesten en verder kalibreren.
- [ ] Op basis van vanaf v1 opgebouwde EVSE- én locatie-duurverdelingen onderzoeken of betrouwbare **vrijkomkansen binnen 15/30/60 minuten** (per EVSE en rechtstreeks voor een volledig onbeschikbare locatie) en **kans dat een nu vrije EVSE vrij blijft** kunnen worden geschat; pas tonen na kalibratie en voldoende data.

## Fase 5 — Notificaties (uitgesteld)

- [ ] [CW-ALERT-001](ALERTS.md) implementeren: Schietspoel 18, **minstens één vrij, eenmalig na geobserveerde nulstatus**.
- [ ] Afwegen: lokale Android-controle (beperkingen achtergrondwerk), FCM-topics, of serverabonnementen.
- [ ] Deduplicatie, versheid, gemiste/offline gebeurtenissen en toestelherstart testen.
- [ ] Geen keuze voor serveraccounts of pusharchitectuur vooraf afdwingen.

## Fase 6 — Optionele accounts, synchronisatie en betaalde gebruikers

- [ ] Productvalidatie: is multi-device sync / betaalde functionaliteit zinvol?
- [ ] Eventueel **aparte gebruikersdatabase** voor accounts, cloudfavorieten, notificatieabonnementen en rechten.
- [ ] Eventueel abonnementen/betaalde tiers, pas na onderzoek naar gebruikerswaarde, beveiliging, privacy en facturatie.
- [ ] De landelijke laadpuntdatabase en v1-API blijven logisch gescheiden van persoonlijke opslag.

## Besluiten en open vragen

**Besloten 22 oktober 2026:** **statistieken en historische weekdag-/uur-beschikbaarheidskansen worden in v1 ook in de Android-app getoond**, mits voldoende metingen; geen garantie op een vrijkomende aansluiting binnen een tijdsinterval. Zelfstandige Android-app en widget, **landelijke database voor de app read-only**, importworker met gescheiden schrijfrechten, **favorieten alleen lokaal op Android**, **geen accounts en geen notificaties in v1**. Mogelijk later een afzonderlijke gebruikersdatabase en betaalde gebruikers. Geen Athlon-token of laadsessiebetaling.

**Vastgelegd op 22 oktober 2026:** actuele catalogus voor heel Nederland; actuele tarieven, geen tariefhistorie; compacte landelijke EWMA-bezettingsprofielen per **EVSE én locatie** (168 weekdag/uur-slots elk, beginwaarde α=0,20); op locatieniveau rechtstreeks gemeten kans op ten minste één vrije aansluiting; per EVSE uptime via een rollend **90-daags** venster met compacte dagtotalen; geen langdurige ruwe eventhistorie. De aanvullende compacte locatiehistogrammen voor nul-vrije-perioden worden eveneens landelijk opgebouwd. Favorieten beperken de landelijke statistiekdekking niet.

**Nog te beslissen:** toekomstige multi-user/auth-strategie (niet v1); fysieke opslagvorm profielen en daadwerkelijke PostgreSQL-opslag; kaart-/tileprovider; rechten voor distributie; tariefdekking; exacte werkelijke importfrequentie; wat te doen als NDW EVSE-status ontbreekt.

### Capaciteitsindicatie (nog te toetsen)

Rekenscenario, **geen landelijke NDW-telling**: 80.000 locaties × 168 = 13,44 miljoen locatievakken; 196.000 EVSE's × 168 = 32,93 miljoen EVSE-vakken. Samen ~46,37 miljoen profielwaarden. Met 12 bytes per waarde circa **556 MB ruwe profielen**, exclusief relationele/indexoverhead, actuele catalogus, dagtotalen, back-ups, WAL en importwerkruimte. De oude raming van 1–3,5 GB voor het gehele systeem wordt **niet** als actuele raming aangehouden. Meet dit met een echte PostgreSQL-proefimport.

## Onderzoeksbewijs tot nu toe (21 oktober 2026)

Athlon Charge/Deftpower leverde voor Schietspoel 18:
- API: `GET https://portal-api.deftpower.com/api/services/app/Locations/GetLocationForView?id=0cb0c8d6-2e8e-50c6-9d6b-7f441ae0c127`
- API vereist bearer-authenticatie; **niet** gebruiken als primaire bron vanwege sessie-/tokenafhankelijkheid.
- `locationId=f08bed59-be4b-42d4-8b9b-423f03f82bc6`, coördinaten circa `51.767547,5.529763`; EVSE's `NL*NUO*EALF*0005002*1` en `*2`.
- Beide EVSE's waren `AVAILABLE` in aangeleverd voorbeeld, met Type 2 AC circa 11 kW.

De NDW GeoJSON-test voor hetzelfde kaartgebied leverde:
- `NL-NUO-f08bed59-be4b-42d4-8b9b-423f03f82bc6` — Schietspoel 18, Vattenfall InCharge; **2 vrij van 2**, laatste status `2026-10-21T12:09:14Z`, 11,04 kW.
- `NL-EFL-6086c9b1d903a9165aabebab` — Oostwal 177, E-Flux by Road; **2 vrij van 2** in dezelfde test.
- Locatie, capaciteit en statusupdate bij Schietspoel sloten aan op de Athlon-payload.

**Bewijsgrens:** dit bevestigt de geografische/samengevatte NDW-data voor één moment, **nog niet** de inhoud van de landelijke individuele OCPI-dataset, live monitoring, echte operationele statuscategorisering of tarieven. `open:false` in GeoJSON is niet zonder nadere semantische toets een bewijs van storing.

### Bronlinks

- [NDW Open Data](https://opendata.ndw.nu/)
- [NDW charging locations GeoJSON gzip](https://opendata.ndw.nu/charging_point_locations.geojson.gz)
- [NDW OCPI Locations gzip](https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz)
- [NDW OCPI Tariffs gzip](https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz)

De exacte NDW geografische endpointparameters moeten voor de implementatie tegen de actuele documentatie worden gevalideerd.


## Data First — afronding 29 oktober 2026

De data-first implementatiereeks is inhoudelijk afgerond. De oude interval-/duurstatistiekketens uit #72/#11 zijn via PR #89/#90 retired en vormen geen actuele runtime-architectuur meer. De integrale audit vond daarna nog twee concrete functionele gaten; die zijn gericht opgelost in PR #92 (tariefaanwezigheid) en PR #93 (onvolledige connectorcollecties per EVSE). NDW/OCPI blijft de bron van waarheid; brondata wordt maximaal behouden en lokale bronfouten worden zo klein mogelijk geïsoleerd.

**Volgende productprioriteit:** Android hervatten. Open PR #61 eerst herbeoordelen tegen het definitieve tariefcontract, source freshness, operationele EVSE-telling en connectorsemantiek; daarna alleen de nog passende UX-wijzigingen behouden en op een fysiek Samsung-toestel valideren. PR #54 blijft een zelfstandige documentatienotitie en #79 een uitgestelde monitoring-roadmaptaak.

**Releasegrens:** PR #92 is op de NAS bevestigd. PR #93 is op main gemerged, maar de NAS-uitrol daarvan blijft een afzonderlijk expliciet beslismoment. Geen merge of productie-ingreep zonder review en akkoord.
