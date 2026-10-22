# ChargeWatch — Roadmap en onderzoeksagenda

**Status:** Plan; geen werk gestart of afgerond. Fasen zijn afhankelijk van de resultaten van datavalidatie.

## Fase 0 — Datafundament (eerste stap)

- [ ] Landelijk OCPI Locations gzip-bestand downloaden en de echte JSON-structuur inspecteren.
- [ ] Schietspoel 18 terugvinden en individuele EVSE-statussen vergelijken met de bestaande Athlon-JSON.
- [ ] OCPI Tariffs-download parsen en de getoonde tariff-ID aan daadwerkelijke componenten koppelen.
- [ ] Samengestelde identificatie (OCPI country/party/location) en updategedrag testen.
- [ ] Updatefrequentie, bestandsgrootte, geheugenverbruik en conditionele HTTP-requests meten.
- [ ] NDW-licentie, attributie en documentatie/API-stabiliteit vastleggen.
- [ ] Definitieve importcontracten, technisch datamodel en API-schema opstellen.

**Exit criteria:** succesvolle reproduceerbare ingest, betrouwbare statusinterpretatie, inzicht in datasetlimieten en akkoord op dataschema.

## Fase 1 — Backend & opslag

- [ ] ASP.NET Core .NET 10 solution en Docker Compose basis.
- [ ] PostgreSQL + PostGIS, migraties en import staging.
- [ ] Herhaalbare, transactionele data-import met monitoring.
- [ ] Geoquery, locaties, details, filters, favorieten, alternatieven.
- [ ] Statushistorie, alert-engine en transactionele notificatie-outbox.
- [ ] Auth/device-registratie en CI/tests.

## Fase 2 — Android & MVP 0.1

- [ ] Kotlin + Compose Android-client met kaart en filters.
- [ ] Favorieten en laadpuntdetails.
- [ ] Widget met 2–5 favorieten, versheid en verversen.
- [ ] Pushregistratie en one-shot vrijkom-alert.
- [ ] Test op echte Android-telefoon en deployment NAS.

**MVP exit criteria:** Schietspoel 18 zoeken → favoriet maken → widget bekijken → melding bij later vrijkomen na waargenomen 0 vrij → alternatieven tonen; oude data duidelijk markeren.

## Fase 3 — Historie & statistieken

- [ ] Dashboard met beschikbaarheid per dagdeel/weekdag en storingsduur.
- [ ] Retentie, aggregaties, betrouwbaarheidsindicator en datadekking.

## Fase 4 — Tarieven & vergelijking

- [ ] OCPI-tarieven en voorwaarden nauwkeurig verwerken.
- [ ] Kostencalculator per geschatte laadsessie; contract-/laadpasafhankelijkheid duidelijk tonen.
- [ ] Sortering alternatieven op onderbouwde kosten.

## Fase 5 — Beschikbaarheid voorspellen

- [ ] Baseline statistisch model op dag-/tijdpatronen.
- [ ] Kansen kalibreren, backtesten en onzekerheid tonen.
- [ ] Pas later model voor vrijkomen in 15/30 minuten op basis van voldoende historie.

## Besluiten en open vragen

**Voorgesteld:** zelfstandige Android-app, native widget, eigen .NET 10-backend op NAS, PostgreSQL/PostGIS, NDW als primaire dataset, geen Athlon-token of laadsessiebetaling.

**Nog te beslissen:** privé of multi-user; auth-strategie; exacte retentie; kaart-/tileprovider; rechten voor distributie; tariefdekking; exacte werkelijke importfrequentie; wat te doen als NDW EVSE-status ontbreekt.

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
