# ChargeWatch

**Android-first laadpaalassistent** voor openbare laadlocaties in Nederland: actuele beschikbare laadpunten, huidige tarieven, landelijke kaart, bestemming-/GPS-zoeken, filters, nabije alternatieven en lokale favorieten met een native Android-widget.

> **Actuele status — 6 november 2026:** landelijke kaart en clustering op NAS gevalideerd en op Samsung geaccepteerd (#113); native widgets (#101) en historische EWMA-statistieken (#103) afgerond binnen v1. API-PR #130 en Android-PR #131 gemerged. **Optie A:** historische beschikbaarheid en EVSE-betrouwbaarheid zijn experimentele EWMA-indicaties, geen gekalibreerde voorspellingen of werkelijke uptime over 90 dagen. Datakwaliteitskalibratie en optionele exacte uptime zijn uitgesteld naar [#132](https://github.com/VanHinthem/ChargeWatch/issues/132). **Voor v1 resteert [#104](https://github.com/VanHinthem/ChargeWatch/issues/104)**: uitvoering in [#134 technische release-audit](https://github.com/VanHinthem/ChargeWatch/issues/134), [#135 geïntegreerde Samsung-kwaliteitsronde](https://github.com/VanHinthem/ChargeWatch/issues/135) en [#136 releasecandidate/go-no-go](https://github.com/VanHinthem/ChargeWatch/issues/136), met expliciet afzonderlijk publicatiebesluit. Een publieke v1-release is nog niet bevestigd.

## Waarom ChargeWatch?

Bekijk snel hoeveel aansluitingen bij een favoriete laadlocatie vrij zijn en ontdek geschikte alternatieven in de buurt. De widget toont betrouwbare `vrij/totaal`-tellingen, bronversheid en bij tijdelijk uitval de laatste bekende geldige status. De historische beschikbaarheids- en betrouwbaarheidswaarden zijn EWMA-indicaties; we claimen hiermee geen nauwkeurig gekalibreerde kans of aantoonbare uptime over 90 dagen.

## Projectdocumentatie

- [Actuele status](docs/STATUS.md) — wat is gemerged, wat is op de NAS bevestigd, en wat is nog open.
- [Roadmap](docs/ROADMAP.md) — afgeronde #103/#113, resterende releasekwaliteit #104 en uitgesteld onderzoek #132.
- [Productvisie en functionele eisen](docs/PRODUCT.md).
- [Technisch ontwerp](docs/ARCHITECTURE.md).
- [Locatie-dataflow en kaartontwerp](docs/LOCATION_DATAFLOW_ARCHITECTURE.md) — historisch ontwerp; de actuele implementatie- en rolloutstatus staat in STATUS.
- [Native widgetontwerp](docs/ANDROID_WIDGET_IMPLEMENTATION_PLAN.md) — inclusief oude Samsung-iteraties en de uiteindelijk gemergede layouts.
- [Historisch statuslog](docs/archive/STATUS_THROUGH_2026-11-05.md) en [oude roadmap](docs/archive/ROADMAP_THROUGH_2026-11-05.md) — bewaard ter reconstructie van tests/besluiten.
- [Notificatieregels — toekomstig](docs/ALERTS.md) — niet opgenomen in v1.

## Versie 1 — scope en voortgang

**Gerealiseerd:** landelijke NDW/OCPI-catalogus en PostgreSQL/PostGIS-read-only API; landelijke kaart/overzicht, zoeken, filters, GPS en vijf nabije laadlocaties; huidige tarieven; lokale favorieten, native Glance-widget en gecombineerde historische beschikbaarheids-/EVSE-betrouwbaarheidsgrafiek. Gerichte `POST /api/v1/locations/by-ndw-id/batch` (maximaal 20 per request), gzip voor JSON en twee onafhankelijke Android-caches (actuele details/tarieven: 2 minuten; historie: 15 minuten, pas bij uitklappen) beperken requests en dataverkeer.

**Nog te valideren vóór release:** de integrale Android-kwaliteitsronde van #104: release-signing/versies, privacy/bronattributie, offline-/foutgevallen, toegankelijkheid, performance/batterij/widget-refresh, netwerkgedrag, beveiliging en Samsung-regressie. Bewaak dat de experimentele EWMA-indicaties nergens als gegarandeerde uptime of gevalideerde voorspelling worden gepresenteerd. De landelijke overview is geactiveerd en in de praktijk getest; de landelijke kaart is standaard. Elke publieke release vraagt expliciet akkoord.

**Niet in v1:** accounts, serverfavorieten, notificaties/push, laadsessies starten, laadpassen of betalingen, kortetermijnvoorspellingen en Android Auto. Een echte 1×1-widget is slechts een toekomstig idee. Exacte rolling-90-day-uptime en EWMA-opbouwcorrectie/kwaliteitskalibratie zijn uitgesteld naar #132.

## Architectuur

- **API:** ASP.NET Core / .NET 10, alleen-lezen REST, Docker Compose op NAS.
- **Data:** PostgreSQL 18 + PostGIS, gescheiden lees-/schrijfrechten; ingelezen NDW/OCPI Locations en Tariffs met actuele laadpunt-/EVSE-/connectorgegevens.
- **Android:** Kotlin, Jetpack Compose/MapLibre, Glance-widget, lokale favorieten en instellingen; gedeelde process-cache en aparte persistente widgetcache voor offline laatst-bekend.
- **Kaart:** landelijke overview is de standaard met clustering, straatniveau-viewports en volledige dekking; clusters tonen aantallen laadlocaties en een groen/rood/onbekend-statusverdeling.

## Bijdragen en uitrol

Werk in beperkte, geteste PR's. CI en een geslaagde Samsung-debugtest zijn geen automatisch productie- of publieksreleaseakkoord. Merge, NAS-deployment, featureflag-activering en distributie vinden alleen plaats na afzonderlijk akkoord. Controleer vóór publieke uitrol NDW-hergebruikvoorwaarden, bronvermelding en kaartproviderlicenties.
