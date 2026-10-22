# ChargeWatch — Technisch ontwerp

**Status:** Voorgestelde architectuur; nog niet gebouwd.

## Hoofdopzet — versie 1

```text
NDW OCPI Locations + Tariffs
             |
   .NET 10 importworker (SCHRIJFT)
             |
    PostgreSQL + PostGIS
    huidige landelijke data,
    EWMA + uptime-statistieken
             |
  read-only databasegebruiker
             |
 ASP.NET Core GET-only REST API
             |
 Android-app + native widget
   lokale favorieten (Room/DataStore)
```

**Read-only betekent:** de **app en haar API** kunnen geen landelijke data wijzigen; de NDW-importworker moet de catalogus en statistieken wel blijven bijwerken. Gebruik aparte databasecredentials/rollen en geen publieke write-endpoints. De client krijgt geen directe databaseverbinding.

**Toekomstige uitbreiding (niet v1):** aparte schrijfbare gebruikers-/abonnementendatabase, optionele accounts, favorietensynchronisatie, notificaties, FCM en eventueel betaalde accounts. Zo blijven landelijke data en gebruikersdata gescheiden.

### Stack

- ASP.NET Core / **.NET 10**, .NET BackgroundService voor imports; EF Core voor normale transacties, gerichte bulkverwerking waar nuttig.
- **PostgreSQL 18 met PostGIS** voor ruimtelijke zoekopdrachten en historie.
- Docker Compose op de **NAS**; starten met lokale ontwikkeling + testdeployment en later eventueel DEV/ACC/PROD.
- REST/OpenAPI; API blijft onafhankelijk van Android.
- Android-native **Kotlin + Jetpack Compose**, **Jetpack Glance**-widget; Room voor lokale gegevens en favorieten-cache; WorkManager voor periodieke widgetverversing.
- FCM/push is **uitgesteld tot na v1**. Periodieke Android-work is niet exact op tijd gegarandeerd.
- Voor kaartweergave kandidaat: MapLibre met een provider waarvoor gebruiksvoorwaarden en tilelimieten zijn gecontroleerd.

**Ontwerpprincipe:** één modulaire backend en database, geen microservices of message broker in MVP.

## Logisch gegevensmodel

| Entiteit | Doel |
|---|---|
| ChargingLocation | Interne sleutel, samengestelde OCPI-identificatie, operator, adres, coördinaten |
| Evse | Afzonderlijke EVSE, bronidentificatie, status, bronstatusmoment |
| Connector | Type, vermogen, stroomsoort en tariefreferenties |
| EvseOccupancyProfile | 168 EWMA-tijdvakken per EVSE (weekdag × uur), bezettingsgraad en kwaliteitsindicatoren |
| LocationOccupancyProfile | 168 EWMA-tijdvakken per locatie; bezettingsgraad én rechtstreeks gemeten kans op >=1 vrije EVSE |
| EvseDailyReliability | Compacte operationele/defecte/onbekende tijd per EVSE per dag, rollend 90 dagen |
| StatisticsAccumulator | Tijdelijk werk-/checkpointmodel om geldige tijdgewogen observaties per uur en import te verwerken |
| EvseDurationSummary | Compacte duurverdelingen en tellers voor beschikbaarheids-, niet-beschikbaarheids- en storingsperioden per EVSE; geen ruwe sessielog |
| Tariff | Alleen huidige OCPI-tariefcomponenten en voorwaarden; geen tariefhistorie |
| ImportRun | Status, timing, aantallen, fouten en gebruikte bronmetadata |

Locatie is niet hetzelfde als EVSE, en EVSE niet hetzelfde als connector. Voor identity gebruiken we bij voorkeur **country_code + party_id + OCPI location ID**, met een stabiele interne sleutel; bron-IDs kunnen wijzigen. De status is uiteindelijk per EVSE, niet per tariefconnector.

## Importpipeline

1. Conditionele GET toepassen waar HTTP ETag/Last-Modified dat ondersteunen; wijzigingsfrequentie eerst meten.
2. Het nationale gzip/JSON-bestand gestreamd downloaden, uitpakken en verwerken zonder volledige landelijke JSON permanent in RAM te houden.
3. Schema en datakwaliteit valideren: IDs, aantallen, tijdstempels, volledigheid en afnemende dekkingsgraad.
4. Data in staging vergelijken met de laatst succesvol gepubliceerde versie.
5. Een complete geldige import atomair publiceren; wijzigingen vastleggen.
6. Huidige EVSE-statussen vergelijken, compacte uuraccumulators bijwerken en veilig publiceren. De importer hoeft in v1 **geen** notificaties of langdurige eventhistorie bij te houden.

**Nooit:** afwezige records in een mislukte of onvolledige import automatisch als defect, verdwenen of vrij markeren. Bij fouten behoudt de API de laatst bekende status met duidelijke stale-indicator.

## Synchronisatievoorstel (te meten)

- Locaties/EVSE: start met circa **5 minuten**, pas aan op daadwerkelijk bronverversingsritme, netwerk en CPU.
- Tarieven: als startpunt circa **elk uur**.
- Android-widget: periodiek rond **15 minuten** (best effort) en handmatige refresh; evt. push-triggered update.
- **Landelijke occupancy-statistiek:** per EVSE én per locatie 168 weekdag-/uurvakken als compacte profielblokken/arrays; uurmetingen eerst tijdgewogen samenstellen en per corresponderend weekvak één EWMA-update uitvoeren. Beginwaarde **α=0,20** als te valideren parameter.
- **Locatiebeschikbaarheid:** EWMA van daadwerkelijk gemeten ‘minstens één EVSE AVAILABLE’; **niet** afleiden door individuele EVSE-bezettingspercentages onafhankelijk te vermenigvuldigen.
- **Betrouwbaarheid:** per EVSE 90 dagen compacte dagtotalen met afzonderlijke operationele, defecte en onbekende tijd. Uptime = operationeel / (operationeel + defect), met voldoende datadekking; onbekend telt niet als beschikbaar of defect.
- **Duur- en overgangsstatistiek:** houd per EVSE huidige toestandsklasse en begindetectietijd bij; registreer bij valide verandering alleen een compacte teller en een duurcategorie (bijvoorbeeld 0–15, 15–30, 30–60, 60–120 minuten, enzovoort) voor voltooide AVAILABLE-, niet-vrije of storingsperioden. Bewaar afzonderlijke tellingen van storingsincidenten, herstel en statuswisselingen. Gebruik uitsluitend perioden met betrouwbaar geobserveerde start en einde; linker-/rechtercensurering en ontbrekende data expliciet behandelen. Een niet-vrije toestand is niet noodzakelijk een actieve laadsessie.
- **Ruwe events:** geen langdurige landelijke eventhistorie. Alleen huidige status, bron-/waarnemingstijd en noodzakelijke duurzame importtoestand opslaan. Een gemiste statuswisseling tussen imports kan niet worden gereconstrueerd.
- **Tarieven:** alleen actuele tarieven, zonder historisering.

Bij 18 MB gzip per vijf minuten betekent volledige herdownload circa 5,2 GB per dag; meet HTTP caching, overweeg toekomstige delta-/pushmogelijkheden. Niet aannemen dat zulke mogelijkheden al beschikbaar zijn.

## Statistische semantiek en opslagbudget

- EVSE-bezetting is iets anders dan locatiedrukte: beide niveaus bijhouden. Voor een locatie de kans op >=1 vrij **rechtstreeks** bepalen uit de gezamenlijke actuele status.
- Alleen geldige, voldoende verse metingen opnemen; tijdvakken met ontbrekende data apart tellen. Bewaar dekking, aantallen en laatste update zodat een profiel na weinig observaties niet even betrouwbaar lijkt als een lang opgebouwd profiel.
- Een individuele status die `AVAILABLE` is, is vrij; `CHARGING` of `BLOCKED` is niet vrij maar niet zonder meer defect. Operationeel, defect en onbekend onderscheiden volgens nog te valideren OCPI-codering.
- **Capaciteitsraming, geen meting:** rekenvoorbeeld 80.000 locaties, 196.000 EVSE's → 13,44 miljoen locatievakken en 32,93 miljoen EVSE-vakken; samen 46,37 miljoen. Bij **12 bytes per profielvak** circa 556 MB *ruwe profieldata*, exclusief PostgreSQL-indexen/overhead en dagtotalen. De eerdere 1–3,5 GB raming was vóór toevoeging van EVSE-profielen en is niet meer als totaalbudget geldig. Werkelijke databaseomvang en benodigde werkruimte in Sprint 0 meten. Compacte arrays/blobs versus individuele rijen expliciet vergelijken.
- **Toekomstige kortetermijnvoorspelling:** duurverdelingen kunnen op termijn conditionele vrijkom- en beschikbaar-blijfkansen ondersteunen, maar het modelleren en kalibreren daarvan wordt **niet** voor v1 beloofd. Dag-/uur- of toestandsafhankelijke verdelingen pas toevoegen als de basisverdelingen onvoldoende voorspellend zijn.
- **Datakwaliteit:** bewaar observatiedekking, versheid, ontbrekende updates en bruikbare aantallen per samenvatting. Meldingen van storing zijn alleen te interpreteren als de bronstatus betrouwbaar is.
- **Opslagimpact:** naar verwachting enkele tientallen MB voor landelijke compacte duurstatistieken, exclusief overhead; valideren met echte nationale EVSE-telling en PostgreSQL-metingen.
- **V1 toont** deze compacte weekdag-/uurstatistieken in de app. Publiceer een percentage alleen bij voldoende valide observaties, met meetdekking en indicatie van modelonzekerheid; exacte minimale drempels in Sprint 0 vastleggen.
- Tijdgewogen momentopnames zijn schattingen; latere overgang binnen het interval is onbekend. Een voorspelling van vertrek binnen 15/30 minuten is met deze aggregaties niet zonder meer onderbouwd.

## Beveiliging en operations

HTTPS, aparte PostgreSQL-rol met alleen SELECT voor de API en schrijfrechten uitsluitend voor de importer, GET-only publieke endpoints, rate limiting en passende toegangsbeveiliging. In v1 geen accounts, device-/pushregistraties, persoonlijke serverdata of Athlon bearer-tokens. Monitoring van imports en staleness blijft nodig. Vastleggen van NDW-hergebruikvoorwaarden, attributie en kaarttegellicenties vóór distributie.

## Concept API

- `GET /api/locations?bbox=...` — kaart en filtering
- `GET /api/locations/{id}` — details, EVSE-status en bronversheid
- `GET /api/locations/{id}/alternatives` — nabijgelegen beschikbare laadlocaties
- `GET /api/locations/{id}/statistics` — **v1**: bezettingsprofielen per EVSE en locatie, 90-daagse uptime, rechtstreeks gemeten kans op >=1 vrij per weekdag/uur, met meetdekking en onvoldoende-data-indicatie

**Geen favorieten-, accounts-, alerts- of device-API in v1:** favorieten worden lokaal in Android beheerd.

Dit is conceptuele API-vormgeving; authenticatie, paging, responsecontracten en idempotency keys moeten nog uitgewerkt worden.

## Testing en CI

Unit tests voor statusinterpretatie en compacte statistieken, fixture-/contracttests voor OCPI-import, integratietests voor PostgreSQL en mislukte/partiële imports, injecteerbare klok, mocked FCM, geautomatiseerde .NET- en Android-builds. Migraties versioneren en importdata nooit zonder validatie publiceren.
