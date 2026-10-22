# ChargeWatch

**Android-first laadpaalassistent** voor openbare laadlocaties in Nederland, met favorieten, een native widget, slimme meldingen en op termijn historie, tarieven en voorspellingen.

> **Status:** ontwerpfase (oktober 2026). Er is nog geen applicatiecode geschreven.

## Waarom ChargeWatch?

Toon in één oogopslag hoeveel aansluitingen bij favoriete laadpalen beschikbaar zijn. Ontvang een eenmalige melding wanneer een favoriete locatie vrijkomt. Zoek alternatieven in de buurt en ontdek later, op basis van verzamelde historie, waar en wanneer je de beste kans hebt om te laden.

De oorspronkelijke aanleiding is een Android-widget. Het project groeit uit tot een volwaardige app, zonder zelf laadtransacties of betalingen te verwerken.

## Projectdocumentatie

- [Productvisie en functionele eisen](docs/PRODUCT.md) — scope, zes gekozen productfuncties en MVP.
- [Technisch ontwerp](docs/ARCHITECTURE.md) — .NET 10 backend, PostgreSQL/PostGIS, NDW-imports en Kotlin/Compose Android.
- [Notificatieregels](docs/ALERTS.md) — CW-ALERT-001, Schietspoel 18: **eenmalige melding zodra, na 0 vrije aansluitingen, minimaal 1 aansluiting vrij wordt**.
- [Roadmap en onderzoek](docs/ROADMAP.md) — fasen 0–5, validatiechecklist en vastgelegde bronobservaties.

## MVP 0.1 — Discover & Monitor

Kaart, zoeken en filters; laadlocatiedetails; favorieten; widget; eenmalige notificaties; nabije alternatieven; duidelijke dataversheid. Statushistorie wordt vanaf het begin verzameld. Uitgebreide statistieken, tariefvergelijking en voorspellingen volgen in latere fasen.

## Voorlopige architectuur

- **Backend:** ASP.NET Core / .NET 10, als modulaire monoliet, op NAS via Docker Compose.
- **Opslag:** PostgreSQL 18 + PostGIS.
- **Android:** Kotlin, Jetpack Compose, Jetpack Glance-widget, Room-cache en FCM.
- **Data:** NDW/DOT-NL GeoJSON en landelijke OCPI Locations- en Tariffs-data. De individuele OCPI-dataset moet nog in Sprint 0 inhoudelijk gevalideerd worden.

## Eerstvolgende stap

**Sprint 0 — Datafundament:** download en analyseer het landelijke NDW OCPI Locations-bestand, vergelijk de individuele status van Schietspoel 18 met de eerder waargenomen Athlon-gegevens en valideer de tarief- en ID-structuur. Daarna pas starten we de implementatie.

Zie [ROADMAP.md](docs/ROADMAP.md) voor de complete onderzoekschecklist.

## Scopegrens

ChargeWatch wordt voorlopig een zoek-, informatie- en monitoringapp. Laadsessies starten, laadpassen beheren en betalingen vallen buiten scope.

## Licenties en gebruiksvoorwaarden

Voordat de app publiek verspreid wordt, moeten de NDW-hergebruikvoorwaarden, attributie en kaartproviderlicenties gecontroleerd en gedocumenteerd worden.
