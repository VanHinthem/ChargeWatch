# ChargeWatch — Productvisie en requirements

**Status:** Concept v0.1 — besproken 21–22 oktober 2026. Geen productcode geïmplementeerd.

## Visie

ChargeWatch is een Android-first persoonlijke laadassistent voor **openbare laadpunten in Nederland**. De app helpt gebruikers kiezen *waar, wanneer en tegen welke prijs* zij het best kunnen laden. Het oorspronkelijke idee was een Android-widget met de vrije en operationele status van favoriete laadpalen; het product is uitgebreid naar een volwaardige app.

ChargeWatch is primair een **informatie- en monitoring-app**, geen laadpas-, betaal- of sessiestartplatform. Athlon Charge kan het starten en betalen blijven verzorgen. Of ChargeWatch uitsluitend privé of later publiek gebruikt wordt, is nog open.

## Gekozen productfuncties

1. **Kaart, favorieten en widget:** op kaart zoeken naar laadlocaties, filteren op beschikbaarheid, AC/DC, vermogen en connector; details per locatie/EVSE; favoriete locaties toevoegen, hernoemen en sorteren; Android-startschermwidget met meerdere favorieten en dataversheid.
2. **Slimme notificaties:** gebruiker kan een alert aanmaken als een locatie vrijkomt, met eenmalige en later mogelijk doorlopende regels; ook storingen en herstel als toekomstige uitbreidingen. Zie [ALERTS.md](ALERTS.md).
3. **Historie en statistieken:** statusovergangen verzamelen; beschikbaarheid naar dag en tijdstip, storingsduur, bezettingspatronen en betrouwbaarheid inzichtelijk maken.
4. **Alternatieve laadpalen:** nabijgelegen beschikbare alternatieven tonen en rangschikken op afstand, beschikbaarheid, vermogen, betrouwbaarheid en waar onderbouwd prijs.
5. **Laadtarieven vergelijken:** OCPI-tarieven interpreteren en sessiekosten schatten, expliciet rekening houdend met energiekosten, tijd, parkeren, sessiekosten en laadpascontracten. **Openbare CPO-prijs hoeft niet overeen te komen met Athlon-facturering.** Ontbrekende kosten nooit impliciet op nul zetten.
6. **Beschikbaarheid voorspellen:** eerst statistische waarschijnlijkheden per dagdeel/weekdag, vervolgens kansen op vrijkomen binnen een gekozen periode, pas bij voldoende kwalitatieve historie. Voorspellingen tonen met onzekerheid en dekking.

## MVP — v0.1 Discover & Monitor

- Kaart met zoekfunctie, geolocatie/kaartgebied, clustering en basisfilters.
- Locatiedetails: adres, exploitant, EVSE's, stekkertypes, vermogen, actuele status en tijdstip van bronupdate.
- Favorieten beheren en lokaal cachen.
- Native Android-widget voor circa 2–5 favorieten, status en handmatig verversen.
- Eenmalige notificatie bij overgang van nul vrije naar minimaal één vrije aansluiting.
- Beschikbare alternatieven nabij een favoriete locatie.
- Bronstempel, versheid en duidelijke weergave van onbekende/verouderde statussen.
- Achter de schermen statusovergangen opslaan, ook al komen uitgebreide statistieken pas later.

**Niet in MVP:** uitgebreide historische dashboards, tariefvergelijking, voorspellingen, betalingen of starten/stoppen van laadsessies.

### Acceptatievoorbeeld

De gebruiker vindt **Schietspoel 18, Oss**, maakt die favoriet, ziet de beschikbaarheid in de widget, stelt een eenmalige vrijkom-alert in en kan bij een volle locatie alternatieven zoeken. Bij niet-actuele brondata toont ChargeWatch geen misleidende 'live vrij'-status.

## UX-principes

- Met één blik: **x van y aansluitingen vrij**, en hoe oud die informatie is.
- **Vrij**, **niet vrij**, **defect**, **onbekend** en **verouderd** niet met elkaar verwarren.
- Persoonlijke favorieten voorop; tik naar detail en navigatie.
- Geen meldingsspam of onbegrijpelijke voorspelde precisie.
- Op termijn gebruik van dezelfde backend door andere clients mogelijk.

## Open beslissingen

- Privégebruik of later meerdere gebruikers / publieke distributie?
- Definitieve kaart/tileprovider, licenties en toegangsbeheer.
- Hoeveel landelijke historie opslaan, retentie en benodigde NAS-capaciteit?
- Beschikbaarheid van contractspecifieke tarieven.
- Vormgeving, exacte filterset en scherminteracties na datavalidatie.
