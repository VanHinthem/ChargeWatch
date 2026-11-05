# ChargeWatch

**Android-first laadpaalassistent** voor openbare laadlocaties in Nederland: actuele beschikbare laadpunten, huidige tarieven, landelijke kaart, bestemming-/GPS-zoeken, filters, nabije alternatieven en lokale favorieten met een native Android-widget.

> **Actuele status — 5 november 2026:** Android-widget PR [#112](https://github.com/VanHinthem/ChargeWatch/pull/112) is gemerged na Samsung-acceptatie. De read-only API ondersteunt gerichte batchlocatiedetails en HTTP-gzip (publiek via HTTPS bevestigd). De Android-app heeft gedeelde detail-/statistiekcaches. **Nog open vóór v1:** betrouwbare publicatie van statistieken/90-daagse uptime ([#103](https://github.com/VanHinthem/ChargeWatch/issues/103)), de resterende productieactivatie/HTTP-validatie van de landelijke overview ([#113](https://github.com/VanHinthem/ChargeWatch/issues/113)) en de integrale Android releasekwaliteitsronde ([#104](https://github.com/VanHinthem/ChargeWatch/issues/104)). Een definitieve publieke v1-release is **niet** bevestigd.

## Waarom ChargeWatch?

Bekijk snel hoeveel aansluitingen bij een favoriete laadlocatie vrij zijn en ontdek geschikte alternatieven in de buurt. De widget toont betrouwbare `vrij/totaal`-tellingen, bronversheid en bij tijdelijk uitval de laatste bekende geldige status. Historische kans-/uptimegegevens worden alleen gepubliceerd wanneer de datakwaliteit aantoonbaar voldoende is; dit zijn geen voorspellingen van vertrektijden.

## Projectdocumentatie

- [Actuele status](docs/STATUS.md) — wat is gemerged, wat is op de NAS bevestigd, en wat is nog open.
- [Roadmap](docs/ROADMAP.md) — actuele Android-v1-prioriteiten #103/#104 en dataflow-/overview-productiegate #113.
- [Productvisie en functionele eisen](docs/PRODUCT.md).
- [Technisch ontwerp](docs/ARCHITECTURE.md).
- [Locatie-dataflow en kaartontwerp](docs/LOCATION_DATAFLOW_ARCHITECTURE.md) — historisch ontwerp; de actuele implementatie- en rolloutstatus staat in STATUS.
- [Native widgetontwerp](docs/ANDROID_WIDGET_IMPLEMENTATION_PLAN.md) — inclusief oude Samsung-iteraties en de uiteindelijk gemergede layouts.
- [Historisch statuslog](docs/archive/STATUS_THROUGH_2026-11-05.md) en [oude roadmap](docs/archive/ROADMAP_THROUGH_2026-11-05.md) — bewaard ter reconstructie van tests/besluiten.
- [Notificatieregels — toekomstig](docs/ALERTS.md) — niet opgenomen in v1.

## Versie 1 — scope en voortgang

**Gerealiseerd:** landelijke NDW/OCPI-catalogus en PostgreSQL/PostGIS-read-only API; kaart, zoeken, filters, GPS en vijf nabije laadlocaties; huidige tarieven; per toestel opgeslagen favorieten en native Glance-widget. Gerichte `POST /api/v1/locations/by-ndw-id/batch` (maximaal 20 per request), gzip voor JSON en twee onafhankelijke Android-caches (actuele details/tarieven: 2 minuten; historie: 15 minuten, pas bij uitklappen) beperken requests en dataverkeer.

**Nog te valideren vóór release:** historische bezettingskansen en 90-daagse EVSE-uptime op basis van meetdekking/statuskwaliteit; landelijke overview achter `CW_ENABLE_MAP_OVERVIEW` met gecontroleerde NAS-HTTP-meting en featureflagkeuze; release-signing, privacy/bronattributie, offline/fouten, toegankelijkheid en integrale Samsung-regressie. De nieuwe kaartmodus is momenteel optioneel en Android kiest standaard de bestaande kaart. Featureflag-activering op de NAS is niet bevestigd.

**Niet in v1:** accounts, serverfavorieten, notificaties/push, laadsessies starten, laadpassen of betalingen, kortetermijnvoorspellingen en Android Auto. Een echte 1×1-widget is slechts een toekomstig idee.

## Architectuur

- **API:** ASP.NET Core / .NET 10, alleen-lezen REST, Docker Compose op NAS.
- **Data:** PostgreSQL 18 + PostGIS, gescheiden lees-/schrijfrechten; ingelezen NDW/OCPI Locations en Tariffs met actuele laadpunt-/EVSE-/connectorgegevens.
- **Android:** Kotlin, Jetpack Compose/MapLibre, Glance-widget, lokale favorieten en instellingen; gedeelde process-cache en aparte persistente widgetcache voor offline laatst-bekend.
- **Kaart:** bestaande viewportmodus plus feature-gated landelijke overview; clusters tonen aantallen laadlocaties en een betrouwbare groen/rood/onbekend-statusverdeling.

## Bijdragen en uitrol

Werk in beperkte, geteste PR's. CI en een geslaagde Samsung-debugtest zijn geen automatisch productie- of publieksreleaseakkoord. Merge, NAS-deployment, featureflag-activering en distributie vinden alleen plaats na afzonderlijk akkoord. Controleer vóór publieke uitrol NDW-hergebruikvoorwaarden, bronvermelding en kaartproviderlicenties.
