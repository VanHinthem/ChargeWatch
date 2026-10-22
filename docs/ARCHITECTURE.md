# ChargeWatch — Technisch ontwerp

**Status:** Voorgestelde architectuur; nog niet gebouwd.

## Hoofdopzet

```text
NDW/DOT-NL OCPI Locations + Tariffs       NDW GeoJSON API (optioneel)
                  |                                  |
                  +--------------+-------------------+
                                 v
                 .NET 10 background import worker
                 download → decompress → validate
                     stage → diff → publish
                                 |
                       PostgreSQL 18 + PostGIS
                                 |
               ASP.NET Core REST API (modulaire monoliet)
                /               |                 \
         zoek/catalogus     favorieten       alerts/historie
                \               |                 /
                       HTTPS / authenticatie
                                 |
                   Android (Kotlin, Jetpack Compose)
                   Room-cache + Glance-widget
                                 |
                         Firebase Cloud Messaging
```

### Stack

- ASP.NET Core / **.NET 10**, .NET BackgroundService voor imports; EF Core voor normale transacties, gerichte bulkverwerking waar nuttig.
- **PostgreSQL 18 met PostGIS** voor ruimtelijke zoekopdrachten en historie.
- Docker Compose op de **NAS**; starten met lokale ontwikkeling + testdeployment en later eventueel DEV/ACC/PROD.
- REST/OpenAPI; API blijft onafhankelijk van Android.
- Android-native **Kotlin + Jetpack Compose**, **Jetpack Glance**-widget; Room voor lokale gegevens en favorieten-cache; WorkManager voor periodieke widgetverversing.
- **Firebase Cloud Messaging (FCM)** voor servergestuurde pushmeldingen. Periodieke Android-work is niet exact op tijd gegarandeerd.
- Voor kaartweergave kandidaat: MapLibre met een provider waarvoor gebruiksvoorwaarden en tilelimieten zijn gecontroleerd.

**Ontwerpprincipe:** één modulaire backend en database, geen microservices of message broker in MVP.

## Logisch gegevensmodel

| Entiteit | Doel |
|---|---|
| ChargingLocation | Interne sleutel, samengestelde OCPI-identificatie, operator, adres, coördinaten |
| Evse | Afzonderlijke EVSE, bronidentificatie, status, bronstatusmoment |
| Connector | Type, vermogen, stroomsoort en tariefreferenties |
| EvseStatusHistory | Statusovergangen, brontijd en ons detectietijdstip |
| Tariff | OCPI-tariefcomponenten, voorwaarden en geldigheidsperiode |
| Favorite | Favoriete locatie, gebruikers-/apparaatrelatie, naam en volgorde |
| AlertSubscription | Locatie, drempel, type, one-shot, toestand en lifecycle |
| DeviceRegistration | Pushregistratie en apparaatidentiteit |
| NotificationOutbox | Duurzame te verzenden gebeurtenis, retries en idempotentie |
| ImportRun | Status, timing, aantallen, fouten en gebruikte bronmetadata |

Locatie is niet hetzelfde als EVSE, en EVSE niet hetzelfde als connector. Voor identity gebruiken we bij voorkeur **country_code + party_id + OCPI location ID**, met een stabiele interne sleutel; bron-IDs kunnen wijzigen. De status is uiteindelijk per EVSE, niet per tariefconnector.

## Importpipeline

1. Conditionele GET toepassen waar HTTP ETag/Last-Modified dat ondersteunen; wijzigingsfrequentie eerst meten.
2. Het nationale gzip/JSON-bestand gestreamd downloaden, uitpakken en verwerken zonder volledige landelijke JSON permanent in RAM te houden.
3. Schema en datakwaliteit valideren: IDs, aantallen, tijdstempels, volledigheid en afnemende dekkingsgraad.
4. Data in staging vergelijken met de laatst succesvol gepubliceerde versie.
5. Een complete geldige import atomair publiceren; wijzigingen vastleggen.
6. Statusovergangen schrijven en alert-engine aanroepen; notificaties via outbox.

**Nooit:** afwezige records in een mislukte of onvolledige import automatisch als defect, verdwenen of vrij markeren. Bij fouten behoudt de API de laatst bekende status met duidelijke stale-indicator.

## Synchronisatievoorstel (te meten)

- Locaties/EVSE: start met circa **5 minuten**, pas aan op daadwerkelijk bronverversingsritme, netwerk en CPU.
- Tarieven: als startpunt circa **elk uur**.
- Android-widget: periodiek rond **15 minuten** (best effort) en handmatige refresh; evt. push-triggered update.
- Statushistorie: alleen wijzigingen vastleggen (met aparte bron- en waarnemingstijden). Als twee veranderingen tussen imports plaatsvinden, missen we die: statistiek kent dus onzekerheid.
- Retentievoorstel: circa **90 dagen** ruwe historie, daarna langer bewaarde aggregaties; beslissen na volumemeting.

Bij 18 MB gzip per vijf minuten betekent volledige herdownload circa 5,2 GB per dag; meet HTTP caching, overweeg toekomstige delta-/pushmogelijkheden. Niet aannemen dat zulke mogelijkheden al beschikbaar zijn.

## Beveiliging en operations

HTTPS, authenticatie voor persoonlijke favorieten/notificaties, beperkte API-toegang, rate limiting, push token lifecycle, geen onnodige persoonsgegevens of Athlon bearer-tokens, monitoring van imports en staleness. Vastleggen van NDW-hergebruikvoorwaarden, attributie en kaarttegellicenties vóór distributie.

## Concept API

- `GET /api/locations?bbox=...` — kaart en filtering
- `GET /api/locations/{id}` — details, EVSE-status en bronversheid
- `GET /api/locations/{id}/alternatives` — nabijgelegen beschikbare laadlocaties
- `GET /api/favorites`, `POST /api/favorites`, `DELETE /api/favorites/{id}`
- `POST /api/alerts`, `GET /api/alerts`, `GET /api/alerts/{id}`, `DELETE /api/alerts/{id}`
- `POST /api/devices`, `GET /api/notifications`

Dit is conceptuele API-vormgeving; authenticatie, paging, responsecontracten en idempotency keys moeten nog uitgewerkt worden.

## Testing en CI

Unit tests voor statusinterpretatie en alert-engine, fixture-/contracttests voor OCPI-import, integratietests voor PostgreSQL en mislukte/partiële imports, injecteerbare klok, mocked FCM, geautomatiseerde .NET- en Android-builds. Migraties versioneren en importdata nooit zonder validatie publiceren.
