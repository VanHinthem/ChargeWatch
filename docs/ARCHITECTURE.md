# ChargeWatch — Technisch ontwerp

**Status:** Importer, PostgreSQL/PostGIS, read-only API, compacte statistieken en Android-client zijn operationeel. De data-audit tot en met tarief- en connectoraanwezigheid is geïmplementeerd; productie-uitrol blijft per release een afzonderlijk beslismoment.

## Mapfilters — read-only API additie (issue #95, PR #97)

Bestaande `GET /api/v1/map/viewport` en `GET /api/v1/locations` accepteren optionele herhaalbare `connectorType=type2|ccs2|chademo|other`, `mode=ac|dc` en `operator=<bronoperatornaam>`, exclusieve `power=lt50|gte50|gte150|gte300`, en `minFree=0|1|2`. Binnen één filtergroep is selectie OF, tussen groepen EN; connectortype + AC/DC + vermogen matchen op dezelfde actieve connector binnen één actieve operationele EVSE. Per EVSE is maximaal één beschikbaarheidsmatch. Vermogensbanden gebruiken expliciet **en** berekend `effectivePowerWatts` identiek, ontbrekende/0-watts waarden matchen geen band. Operatornamen komen uit ongewijzigde `charging_location.operator_name` en een read-only `GET /api/v1/operators`. Locaties worden gefilterd vóór de 1.000-viewport-cap en vóór de vijf nearest-resultaten.

Zonder filter blijft de bestaande API/NDW-fallback/marker- en locatietelling identiek. Met filters bevestigt de API `X-ChargeWatch-Filter-Version: 1` op beide success-responses; een oude of onbereikbare NAS-API en directe NDW-GeoJSON-resultaten mogen niet stilzwijgend als gefilterde locaties worden getoond. Filters worden alleen in de Android-appsessie onthouden; geen opslag in DB of DataStore. Deze nieuwe endpoints worden pas beschikbaar op de NAS na expliciet afzonderlijk deploymentakkoord en een backendrelease.

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

### NDW als bron van waarheid en maximaal databehoud

- NDW/OCPI is de primaire bron van waarheid voor de actuele landelijke catalogus, statussen en brontarieven.
- Bewaar ontvangen brondata, identiteiten en relaties zo veel mogelijk; onbekende velden of tijdelijk afwezige records zijn geen reden voor destructieve verwijdering.
- Verwerk geldige delen van een snapshot ook wanneer één lokaal onderdeel onbruikbaar is. Isoleer fouten op het kleinst veilige niveau, zodat één EVSE of connector de landelijke import niet blokkeert.
- Gebruik onvolledige of malformeerde lokale data nooit als bewijs van verdwijning. Voor die entiteit blijft de laatst geldige data staan en schuift presence-evidence niet op.
- Neem afwezigheid alleen over uit een daarvoor voldoende betrouwbare snapshot, met toepasselijke minimumaantallen, krimpguards, replaybescherming en hysterese.
- Toon zo veel mogelijk betrouwbare informatie, maar verzin niets: onbekend is niet nul, vrij, defect of actueel. Scheid broninhoud, aanwezigheid, versheid en afgeleide zekerheid.
- Behouden historische bronrijen zijn diagnostische continuïteit, geen onbeperkte tijdreeks en geen bewijs dat de inhoud nog actueel is.

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
| LocationFullOccupancySummary | Per locatie duurcategorieën van perioden met **nul vrije EVSE's**, overgangsteller naar >=1 vrij, begindetectietijd van huidige volledig-bezette periode en datakwaliteit |
| Tariff | Actuele OCPI-tariefcomponenten en voorwaarden; verdwenen bronrijen inactief behouden voor identiteit/diagnose, zonder tarieftijdreeks |
| ImportRun | Status, timing, aantallen, fouten en gebruikte bronmetadata |

Locatie is niet hetzelfde als EVSE, en EVSE niet hetzelfde als connector. Voor identity gebruiken we bij voorkeur **country_code + party_id + OCPI location ID**, met een stabiele interne sleutel; bron-IDs kunnen wijzigen. De status is uiteindelijk per EVSE, niet per tariefconnector.

## Importpipeline

1. Conditionele GET toepassen waar HTTP ETag/Last-Modified dat ondersteunen; wijzigingsfrequentie eerst meten.
2. Het nationale gzip/JSON-bestand gestreamd downloaden, uitpakken en verwerken zonder volledige landelijke JSON permanent in RAM te houden.
3. Schema en datakwaliteit valideren: IDs, aantallen, tijdstempels, volledigheid en afnemende dekkingsgraad.
4. Data in staging vergelijken met de laatst succesvol gepubliceerde versie; structurele lokale fouten per entiteit markeren.
5. Een geaccepteerde snapshot atomair publiceren. Geldige delen blijven verwerkbaar; onbetrouwbare lokale collecties worden uitsluitend voor destructieve presence-evidence uitgesloten.
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
- **Locatiebrede bezettingsperioden:** bepaal bij iedere valide gezamenlijke locatiesnapshot of er **0** of **>=1** vrije EVSE's zijn. Houd start en duur van ononderbroken perioden met 0 vrij bij; bij overgang naar >=1 vrij verhoog je compacte histogram-buckets/tellers en sluit je de periode af. Hiermee meten we rechtstreeks hoe lang een locatie zonder vrije aansluiting blijft, zonder uit te gaan van onafhankelijke EVSE's. Storingen kunnen ook 0 vrij opleveren: onderscheid **volledig bezet** van **geen beschikbare aansluiting door storing/blokkade** en label of filter dergelijke perioden voor voorspellingen.
- **Onvolledige perioden:** onbetrouwbare of niet-verse gezamenlijke status pauzeert de geldige observatie; ontbrekende begin- of eindobservatie niet stilzwijgend als volledige duur registreren. Bij een wijzigend aantal EVSE's aan de locatie en bij importgaten de statistische kwaliteitscontrole toepassen.
- **Ruwe events:** geen langdurige landelijke eventhistorie. Alleen huidige status, bron-/waarnemingstijd en noodzakelijke duurzame importtoestand opslaan. Een gemiste statuswisseling tussen imports kan niet worden gereconstrueerd.
- **Tarieven:** alleen actieve tarieven als actuele prijs presenteren. Verdwenen bronpayloads en referenties mogen inactief bewaard blijven voor identiteit en diagnose, zonder een tarieftijdreeks op te bouwen.

Bij 18 MB gzip per vijf minuten betekent volledige herdownload circa 5,2 GB per dag; meet HTTP caching, overweeg toekomstige delta-/pushmogelijkheden. Niet aannemen dat zulke mogelijkheden al beschikbaar zijn.

## Statistische semantiek en opslagbudget

- EVSE-bezetting is iets anders dan locatiedrukte: beide niveaus bijhouden. Voor een locatie de kans op >=1 vrij **rechtstreeks** bepalen uit de gezamenlijke actuele status.
- Alleen geldige, voldoende verse metingen opnemen; tijdvakken met ontbrekende data apart tellen. Bewaar dekking, aantallen en laatste update zodat een profiel na weinig observaties niet even betrouwbaar lijkt als een lang opgebouwd profiel.
- Een individuele status die `AVAILABLE` is, is vrij; `CHARGING` of `BLOCKED` is niet vrij maar niet zonder meer defect. Operationeel, defect en onbekend onderscheiden volgens nog te valideren OCPI-codering.
- **Capaciteitsraming, geen meting:** rekenvoorbeeld 80.000 locaties, 196.000 EVSE's → 13,44 miljoen locatievakken en 32,93 miljoen EVSE-vakken; samen 46,37 miljoen. Bij **12 bytes per profielvak** circa 556 MB *ruwe profieldata*, exclusief PostgreSQL-indexen/overhead en dagtotalen. De eerdere 1–3,5 GB raming was vóór toevoeging van EVSE-profielen en is niet meer als totaalbudget geldig. Werkelijke databaseomvang en benodigde werkruimte in Sprint 0 meten. Compacte arrays/blobs versus individuele rijen expliciet vergelijken.
- **Toekomstige kortetermijnvoorspelling:** EVSE-duurverdelingen en **direct gemeten duur van perioden met nul vrije aansluitingen per locatie** kunnen op termijn conditionele vrijkom- en beschikbaar-blijfkansen ondersteunen, maar het modelleren en kalibreren daarvan wordt **niet** voor v1 beloofd. Dag-/uur- of toestandsafhankelijke verdelingen pas toevoegen als de basisverdelingen onvoldoende voorspellend zijn.
- **Datakwaliteit:** bewaar observatiedekking, versheid, ontbrekende updates en bruikbare aantallen per samenvatting. Meldingen van storing zijn alleen te interpreteren als de bronstatus betrouwbaar is.
- **Opslagimpact:** naar verwachting enkele tientallen MB voor landelijke compacte EVSE-duurstatistieken, exclusief overhead; aanvullende locatiebrede duurverdelingen zijn bij bijvoorbeeld 80.000 locaties × 128 bytes circa **10 MB ruwe waarden**, exclusief database-overhead. Dit is een *rekenscenario*, geen gemeten werkelijke grootte. Beide valideren met PostgreSQL-proefmetingen.
- **V1 toont** deze compacte weekdag-/uurstatistieken in de app. Publiceer een percentage alleen bij voldoende valide observaties, met meetdekking en indicatie van modelonzekerheid; exacte minimale drempels in Sprint 0 vastleggen.
- Tijdgewogen momentopnames zijn schattingen; latere overgang binnen het interval is onbekend. Een voorspelling van vertrek binnen 15/30 minuten is met deze aggregaties niet zonder meer onderbouwd.

## Beveiliging en operations

HTTPS, aparte PostgreSQL-rol met alleen SELECT voor de API en schrijfrechten uitsluitend voor de importer, GET-only publieke endpoints, rate limiting en passende toegangsbeveiliging. In v1 geen accounts, device-/pushregistraties, persoonlijke serverdata of Athlon bearer-tokens. Monitoring van imports en staleness blijft nodig. Vastleggen van NDW-hergebruikvoorwaarden, attributie en kaarttegellicenties vóór distributie.

## Read-only API — PR #22

De eerste API-versie gebruikt prefix `/api/v1` en bevat uitsluitend publieke GET-routes:

- `GET /api/v1/locations?q=...&bbox=minLon,minLat,maxLon,maxLat&latitude=...&longitude=...&radiusMeters=...&limit=...` — begrensde kaart-/zoekresultaten (maximaal 200), actuele EVSE-samenvatting, bronmoment, stale-indicatie en optionele afstand.
- `GET /api/v1/locations/{id}` — locatie, EVSE's, connectoren, actuele statussen en gekoppelde actuele OCPI-tarieven.
- `GET /api/v1/locations/{id}/statistics` — compact rechtstreeks gemeten beschikbaarheidsprofiel per locatie en EVSE, inclusief sample/changed/unchanged-tellingen en laatste sample. De API claimt nog geen minimum betrouwbaarheid of exact vrijkommoment.
- `GET /api/v1/data-status` — laatste succesvolle import, ouderdom/stale-indicatie en actuele catalogusaantallen.

De API verbindt uitsluitend met PostgreSQL als `chargewatch_read`; startup faalt bij een andere rol of schrijftoegang. HTTPS-redirection en IP-gebaseerde rate limiting zijn onderdeel van de service. De stale-drempel is configureerbaar via `CW_API_STALE_AFTER_MINUTES` (standaard 15 minuten). De DB-connection string staat uitsluitend server-side in `CW_API_CONNECTION`.

**Niet in deze API:** favorieten, eigen namen, sortering, accounts, alerts, device-registratie, synchronisatie, import-/beheerwrites of geheime app-keys. Favorieten blijven lokaal op Android. Alternatieven worden niet als aparte serverroute in PR #22 toegevoegd; de client kan de nabijheidszoekroute gebruiken en latere ranking blijft een afzonderlijke feature.

PR #22 is uitsluitend voor interne ontwikkeling/test. Publieke deployment en Google Play Integrity volgen pas na apart ontwerp en expliciet akkoord.

## Testing en CI

Unit tests voor statusinterpretatie en compacte statistieken, fixture-/contracttests voor OCPI-import, integratietests voor PostgreSQL en mislukte/partiële imports, injecteerbare klok, mocked FCM, geautomatiseerde .NET- en Android-builds. Migraties versioneren en importdata nooit zonder validatie publiceren.


### API-datakwaliteit (interne API, oktober 2026)

- Tekstzoekresultaten krijgen **exacte plaatsnaam** vóór prefix- of straat-/operatorhits. Een substring zoals `oss` in een Tilburgs straatadres kan nog voorkomen, maar staat achter de exacte plaats Oss.
- `data-status.lastSuccessfulImportAt` betekent laatste geslaagde gewijzigde import; `lastObservedPollAt` betekent het laatste geaccepteerde snapshot-pollmoment volgens `snapshot_poll_checkpoint`. `ageSeconds` en de algemene `isStale` zijn hierop gebaseerd. Als de collector niets nieuws heeft geobserveerd, is de indicatie conservatief. `sourceUpdatedAt` en locatie-`isStale` houden hun bestaande, **afzonderlijke** semantiek: oud bronwijzigingsmoment (niet noodzakelijk mislukte poll).
- Beschikbaarheids-`availableProbability` blijft **null** zolang het publicatiebeleid `docs/SPRINT3_PUBLICATION_POLICY.md` geen gekalibreerde grens en voldoende geschiedenis heeft. De API behoudt diagnostische sample-/feedtellers. Null is geen 0% kans.
- Tarieven blijven ruwe OCPI-componenten en originele bron-tekst. Geen API-berekening of claim van een consumentenprijs inclusief btw: veld `vat` en vrije tarieftekst kunnen anders zijn afgerond en contractvoorwaarden kunnen gelden. De latere Android-presentatie moet dit expliciet interpreteren.


## Android API-contract — twee soorten versheid

Bij `GET /api/v1/locations` en `GET /api/v1/locations/{id}` geldt:

| JSON-veld | Betekenis | Interpretatie client |
|---|---|---|
| `sourceUpdatedAt` | Recentste bronwijziging van locatie/gekoppelde EVSE's | Informatief tijdstip; status kan langer ongewijzigd zijn |
| `isStale` | Bronwijzigingsmoment ouder dan de geconfigureerde drempel | **Niet** gebruiken als bewijs dat de ingestelde polling is gestopt |
| `feedLastObservedAt` | Recentste geaccepteerde nationale snapshotpoll in `snapshot_poll_checkpoint` | Tijdstip waarop de nationale feed is verwerkt/geobserveerd, niet individuele EVSE-bevestiging |
| `feedIsStale` | Geen nationale poll binnen de drempel, of onbekend | Waarschuwing over algemene feed-actualiteit; `true` bij ontbrekende checkpoint |

Voor de widget onderscheidt de client 'feed recent gecontroleerd' van 'bronstatus voor het laatst gewijzigd'. Zeg **niet** dat één specifieke EVSE bij de meest recente poll fysiek opnieuw geverifieerd is. De API bewijst dat niet. Bij `feedIsStale=true` de getoonde beschikbaarheid als mogelijk verouderd markeren. Geef geen percentage weer bij `availableProbability=null`. Favorieten/eigen namen/volgorde blijven uitsluitend op Android opgeslagen.


## Besluit 24 oktober 2026 — NDW-first Android, eigen API voor verrijking/fallback

De Android-app haalt zichtbare kaartlocaties/statussen **rechtstreeks** op bij de NDW GeoJSON Consumer API en verrijkt **pas bij het openen van details** met ChargeWatch-statistieken. Als NDW niet beschikbaar is of de response niet bruikbaar is, haalt Android de nodige kaart-/locatiedata op bij de read-only ChargeWatch API. Favorieten en aliassen blijven lokaal. De bestaande import/landelijke database blijft bestaan. Details, identificatiemapping, caching en widgetverzoeken moeten nog aan de hand van echte NDW-data worden gevalideerd. De limiet van NDW (10 requests/s) is gedocumenteerd; **per-IP-toepassing is niet bevestigd**. Publieke ChargeWatch API vereist vóór release uitgewerkte Play Integrity Standard-serververificatie, HTTPS, rate limiting, proxy-IP-trust, DB-limieten en tests. Zie [ANDROID_NDW_SECURITY_TESTPLAN.md](ANDROID_NDW_SECURITY_TESTPLAN.md) voor het volledige contract, de open punten, negatieve tests en releasegates.
