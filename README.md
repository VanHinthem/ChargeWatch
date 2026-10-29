# ChargeWatch

**Android-first laadpaalassistent** voor openbare laadlocaties in Nederland, met actuele laadpuntstatus, tarieven, lokale favorieten en een native widget; bezettingsstatistieken, betrouwbaarheidspercentages en historische beschikbaarheidskansen in versie 1; notificaties en accounts later.

> **Status (29 oktober 2026):** het datafundament, de periodieke NDW/OCPI-import en de read-only API draaien; de Android-app is actief in ontwikkeling. Kaart, locatie-details, tarieven/statistieken, lokale favorieten, light/dark, Settings en een eerste laadlocatiezoekfunctie zijn geïmplementeerd in PR #61. De volgende kaart-UX-fase — bestemming zoeken plus filters — staat in issue #95. NAS/productiedeployments blijven afzonderlijke expliciete releasestappen.

## Waarom ChargeWatch?

Toon in één oogopslag hoeveel aansluitingen bij favoriete laadpalen beschikbaar zijn. Zoek alternatieven in de buurt en ontdek met landelijke weekdag-/uurprofielen waar en wanneer je historisch de beste kans hebt om te laden.

De oorspronkelijke aanleiding is een Android-widget. Het project groeit uit tot een volwaardige app, zonder zelf laadtransacties of betalingen te verwerken.

## Projectdocumentatie

- [Productvisie en functionele eisen](docs/PRODUCT.md) — scope, zes gekozen productfuncties en MVP.
- [Technisch ontwerp](docs/ARCHITECTURE.md) — .NET 10 backend, PostgreSQL/PostGIS, NDW-imports en Kotlin/Compose Android.
- [Notificatieregels](docs/ALERTS.md) — CW-ALERT-001, Schietspoel 18: **eenmalige melding zodra, na 0 vrije aansluitingen, minimaal 1 aansluiting vrij wordt**.
- [Roadmap en onderzoek](docs/ROADMAP.md) — fasen 0–5, validatiechecklist en vastgelegde bronobservaties.

## Versie 1 — Read-only laadpuntcatalogus

Kaart en laadlocatiezoeken; laadlocatiedetails inclusief **huidige tarieven**; favorieten **uitsluitend op Android**; widget; nabije alternatieven en duidelijke dataversheid. Google-Maps-achtige bestemmingzoeking en kaartfilters volgen als aparte kaart-UX-fase in issue #95. **Geen notificaties of server-side gebruikersaccounts in v1.** Vanaf de eerste backendversie verzamelen we **compacte landelijke statistieken per EVSE én per locatie** (EWMA per weekdag/uur en 90-daagse uptime-dagtotalen), zonder omvangrijke permanente eventhistorie. **Versie 1 toont ook** de EVSE- en locatiebezettingsprofielen, 90-daagse uptime en de historische kans op minstens één vrije aansluiting per weekdag/uur. Deze indicatie vereist voldoende datadekking; het is geen voorspelling van een vertrekmoment binnen 15/30 minuten. Uitgebreide tariefvergelijking en kortetermijnvoorspellingen volgen eventueel later.

## Voorlopige architectuur

- **Backend:** ASP.NET Core / .NET 10, als modulaire monoliet, op NAS via Docker Compose.
- **Opslag:** PostgreSQL 18 + PostGIS. Importworker schrijft landelijke laadpuntgegevens en compacte statistieken; de app gebruikt uitsluitend een **read-only REST API** met een aparte databasegebruiker met leesrechten.
- **Android:** Kotlin, Jetpack Compose, Jetpack Glance-widget en lokale opslag van favorieten via Room/DataStore. Push/FCM pas later.
- **Data:** NDW/DOT-NL GeoJSON en landelijke OCPI Locations- en Tariffs-data. De individuele OCPI-dataset moet nog in Sprint 0 inhoudelijk gevalideerd worden.

**Opslagbeleid:** alleen actuele tarieven; actuele landelijke EVSE-status; 168 week-/uurvakken per EVSE en per locatie, inclusief direct gemeten kans op minimaal één vrije aansluiting op locatieniveau. Ruwe statusovergangen worden niet langdurig opgeslagen. De eerder genoemde 1–3,5 GB was een voorlopige schatting vóór EVSE-profielen; **een nieuwe raming en proefimport zijn nodig**.

**Later:** notificaties, eventueel een **aparte database voor accounts, favorieten en abonnementen**, en een mogelijke betaalde gebruikerslaag; dit is nadrukkelijk niet nodig voor v1.

## Eerstvolgende stap

**Sprint 0 — Datafundament:** download en analyseer het landelijke NDW OCPI Locations-bestand, vergelijk de individuele status van Schietspoel 18 met de eerder waargenomen Athlon-gegevens en valideer de tarief- en ID-structuur. Daarna pas starten we de implementatie.

Zie [ROADMAP.md](docs/ROADMAP.md) voor de complete onderzoekschecklist.

## Scopegrens

ChargeWatch wordt voorlopig een zoek-, informatie- en monitoringapp. Laadsessies starten, laadpassen beheren en betalingen vallen buiten scope.

## Licenties en gebruiksvoorwaarden

Voordat de app publiek verspreid wordt, moeten de NDW-hergebruikvoorwaarden, attributie en kaartproviderlicenties gecontroleerd en gedocumenteerd worden.
