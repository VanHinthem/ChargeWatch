# ChargeWatch — Productvisie en requirements

**Status:** Concept v0.1 — besproken 21–22 oktober 2026. Geen productcode geïmplementeerd.

## Visie

ChargeWatch is een Android-first persoonlijke laadassistent voor **openbare laadpunten in Nederland**. De app helpt gebruikers kiezen *waar, wanneer en tegen welke prijs* zij het best kunnen laden. Het oorspronkelijke idee was een Android-widget met de vrije en operationele status van favoriete laadpalen; het product is uitgebreid naar een volwaardige app.

ChargeWatch is primair een **informatie- en monitoring-app**, geen laadpas-, betaal- of sessiestartplatform. Athlon Charge kan het starten en betalen blijven verzorgen. Of ChargeWatch uitsluitend privé of later publiek gebruikt wordt, is nog open.

## Gekozen productfuncties

1. **Kaart, favorieten en widget:** op kaart zoeken naar laadlocaties, filteren op beschikbaarheid, AC/DC, vermogen en connector; details per locatie/EVSE; favoriete locaties toevoegen, hernoemen en sorteren; Android-startschermwidget met meerdere favorieten en dataversheid.
2. **Slimme notificaties (na v1):** gebruiker kan een alert aanmaken als een locatie vrijkomt, met eenmalige en later mogelijk doorlopende regels; ook storingen en herstel als toekomstige uitbreidingen. Zie [ALERTS.md](ALERTS.md).
3. **Historie en statistieken:** voor heel Nederland compacte **EWMA-bezettingsprofielen per EVSE én per locatie** (7 dagen × 24 uur) opbouwen. Op locatieniveau de **kans op minstens één vrije EVSE rechtstreeks meten** in plaats van onafhankelijkheid tussen aansluitingen aan te nemen. Per EVSE een **voortschrijdend uptimepercentage over 90 dagen** op basis van compacte dagtotalen bijhouden. Geen onbeperkte ruwe statusgeschiedenis.
4. **Alternatieve laadpalen:** nabijgelegen beschikbare alternatieven tonen en rangschikken op afstand, beschikbaarheid, vermogen, betrouwbaarheid en waar onderbouwd prijs.
5. **Laadtarieven vergelijken:** uitsluitend **actuele tarieven** bewaren, zonder tariefhistorie; OCPI-tarieven interpreteren en sessiekosten schatten, expliciet rekening houdend met energiekosten, tijd, parkeren, sessiekosten en laadpascontracten. **Openbare CPO-prijs hoeft niet overeen te komen met Athlon-facturering.** Ontbrekende kosten nooit impliciet op nul zetten.
6. **Beschikbaarheid voorspellen:** eerst rechtstreeks gemeten historische kans op >=1 vrije EVSE per weekdag/uur met kwaliteits-/dekkingsindicatoren; voorspellingen van vrijkomen binnen 15/30 minuten zijn **geen onderdeel van dit compacte datamodel zonder aanvullend onderzoek**. Voorspellingen tonen met onzekerheid en dekking.

## Compacte aanvullende statistieken (verzamelen vanaf v1)

Per EVSE bouwen we compacte duurverdelingen op voor **niet-vrije/bezettingsperioden**, **vrije perioden** en **storingen**, met storingstellingen, statuswisselingen en datakwaliteitsindicatoren. Zo kunnen we later onderzoeken of een nu bezette aansluiting binnen 15/30/60 minuten vrijkomt, of een vrije aansluiting beschikbaar blijft. **Dit zijn toekomstige conditionele kansen, geen gegarandeerde vertrektijden en niet noodzakelijk v1-schermen.** Beschikbaarheids- en niet-vrije tijd zijn niet hetzelfde als echte laadsessieduur. **Op locatieniveau** bewaren we bovendien compacte histogrammen/tellers van perioden waarin **geen aansluiting beschikbaar** is, en hoe lang het duurt voordat er weer minstens één vrij is. Dit meten we rechtstreeks uit de gezamenlijke EVSE-status, niet als product van individuele waarschijnlijkheden. Defecte/geblokkeerde aansluitingen onderscheiden we van echte bezetting. Zo bereiden we een latere locatievoorspelling voor: kans dat bij een nu volledig onbeschikbare locatie binnen 15/30/60 minuten minstens één aansluiting beschikbaar wordt. Dit blijft buiten de v1-weergave totdat de schatting voldoende gevalideerd is. Geen langdurige ruwe events opslaan.

## Versie 1 — Read-only app en landelijke catalogus

- Kaart met zoekfunctie, geolocatie/kaartgebied, clustering en basisfilters.
- Locatiedetails: adres, exploitant, EVSE's, stekkertypes, vermogen, actuele status en tijdstip van bronupdate.
- Favorieten, eigen namen en volgorde **uitsluitend lokaal op Android** bewaren; geen accounts en geen server-side favorieten.
- Native Android-widget voor circa 2–5 favorieten, status en handmatig verversen.
- **Geen notificaties in versie 1**; CW-ALERT-001 blijft als toekomstige functionaliteit gespecificeerd in [ALERTS.md](ALERTS.md).
- Beschikbare alternatieven nabij een favoriete locatie.
- **Huidige** OCPI-tarieven tonen (indien betrouwbaar koppelbaar); uitgebreide kostenvergelijking volgt later.
- Bronstempel, versheid en duidelijke weergave van onbekende/verouderde statussen.
- Vanaf dag één **landelijke compacte statistische accumulators** bijwerken voor EVSE én locatie, zonder langdurige opslag van ruwe statusovergangen.
- **Statistieken zichtbaar in v1:** gemiddelde bezetting per EVSE en locatie per weekdag/uur (bijvoorbeeld heatmap) en voortschrijdende uptime/betrouwbaarheid per EVSE over 90 dagen.
- **Eenvoudige voorspelling zichtbaar in v1:** historisch geschatte kans op ten minste één beschikbare aansluiting op een gekozen weekdag en uur, rechtstreeks uit het locatie-EWMA-profiel; toon meetdekking, leeftijd en onvoldoende-data-status. Geen gegarandeerde live-beschikbaarheid op een toekomstig moment.

**Niet in versie 1:** notificaties, pushregistratie, gebruikersaccounts, cloudsync van favorieten, uitgebreide tariefvergelijking, **kortetermijnvoorspellingen van vrijkomen binnen 15/30 minuten**, betaalde abonnementen, betalingen of starten/stoppen van laadsessies.

### Acceptatievoorbeeld

De gebruiker vindt **Schietspoel 18, Oss**, maakt die favoriet, ziet de beschikbaarheid in de widget, bekijkt bezettingspatronen en betrouwbaarheid, ziet de historische kans op een vrije aansluiting voor een gekozen dag/uur en kan bij een volle locatie alternatieven zoeken. Bij niet-actuele brondata toont ChargeWatch geen misleidende 'live vrij'-status.

## UX-principes

- Met één blik: **x van y aansluitingen vrij**, en hoe oud die informatie is.
- **Vrij**, **niet vrij**, **defect**, **onbekend** en **verouderd** niet met elkaar verwarren.
- Persoonlijke favorieten voorop; tik naar detail en navigatie.
- Geen meldingsspam of onbegrijpelijke voorspelde precisie.
- Op termijn gebruik van dezelfde backend door andere clients mogelijk.

## Open beslissingen

- Privégebruik of later meerdere gebruikers / publieke distributie? Voor **v1 zijn er geen accounts**.
- Op termijn eventueel een **afzonderlijke schrijfbare gebruikersdatabase** voor accounts, favorieten, abonnementen en betaalde gebruikers; betaalmodel is uitsluitend toekomstidee.
- Definitieve kaart/tileprovider, licenties en toegangsbeheer.
- Exacte opslagrepresentatie en PostgreSQL-grootte van landelijke EVSE-/locatieprofielen; met proefimport en volumemeting valideren.
- EWMA-startinstelling voorlopig α=0,20 per corresponderend week-/uurvak; na echte metingen kalibreren.
- Beschikbaarheid van contractspecifieke tarieven.
- Vormgeving, exacte filterset en scherminteracties na datavalidatie.
