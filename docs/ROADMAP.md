# ChargeWatch — Roadmap en onderzoeksagenda

**Status:** Plan; geen werk gestart of afgerond. Fasen zijn afhankelijk van de resultaten van datavalidatie.

## Fase 0 — Datafundament (eerste stap)

- [ ] Landelijk OCPI Locations gzip-bestand downloaden en de echte JSON-structuur inspecteren.
- [ ] Schietspoel 18 terugvinden en individuele EVSE-statussen vergelijken met de bestaande Athlon-JSON.
- [ ] OCPI Tariffs-download parsen en de getoonde tariff-ID aan daadwerkelijke componenten koppelen.
- [ ] Samengestelde identificatie (OCPI country/party/location) en updategedrag testen.
- [ ] Updatefrequentie, bestandsgrootte, geheugenverbruik en conditionele HTTP-requests meten.
- [ ] NDW-licentie, attributie en documentatie/API-stabiliteit vastleggen.
- [ ] Proefberekening en PostgreSQL-proefimport voor landelijke catalogus, **EVSE- én locatieprofielen (168 vakken per entiteit)** en 90-daagse uptime-dagtotalen; werkelijke tabel-/indexgrootte meten.
- [ ] Statistiekvalidatie: versheid, geldige statusmapping, tijdgewogen uurmeting, ontbrekende data en rechtstreeks gemeten locatiebeschikbaarheid.
- [ ] Definitieve importcontracten, technisch datamodel en API-schema opstellen.

**Exit criteria:** succesvolle reproduceerbare ingest, betrouwbare statusinterpretatie, inzicht in datasetlimieten en akkoord op dataschema.

## Fase 1 — Landelijke database en read-only API (versie 1 fundament)

- [ ] ASP.NET Core .NET 10 solution, importer en Docker Compose op NAS.
- [ ] PostgreSQL + PostGIS: aparte schrijvende importrol en **uitsluitend lezende API-rol**.
- [ ] Betrouwbare, transactionele landelijke OCPI Locations-/Tariffs-import met staging, validatie en monitoring.
- [ ] Actuele locatie-, EVSE-, connector- en tariefdata; geen tariefhistorie.
- [ ] Vanaf de eerste import landelijke 168-vaks EWMA-profielen **per EVSE én per locatie**, en 90-daagse EVSE-uptime-dagtotalen.
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
- [ ] Vrijkomen binnen 15/30 minuten alleen onderzoeken wanneer de data dat draagt.

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

**Vastgelegd op 22 oktober 2026:** actuele catalogus voor heel Nederland; actuele tarieven, geen tariefhistorie; compacte landelijke EWMA-bezettingsprofielen per **EVSE én locatie** (168 weekdag/uur-slots elk, beginwaarde α=0,20); op locatieniveau rechtstreeks gemeten kans op ten minste één vrije aansluiting; per EVSE uptime via een rollend **90-daags** venster met compacte dagtotalen; geen langdurige ruwe eventhistorie. Favorieten beperken de landelijke statistiekdekking niet.

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
