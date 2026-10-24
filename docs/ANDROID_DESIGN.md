# Android app + widget — ontwerp- en goedkeuringsfase

Status: **gezamenlijke ontwerpfase gestart; eerste kaart-/markerkeuzes bevestigd, totaalontwerp nog niet goedgekeurd** (24 oktober 2026).

## UX-besluiten — eerste gezamenlijke sessie (24 oktober 2026)

De onderstaande keuzes zijn tijdens de gezamenlijke ontwerpsessie bevestigd; **dit is geen definitief akkoord op het volledige Android-/widgetontwerp**.

### Bevestigd
- **Kaart is het startscherm.** Bij openen meteen de kaart tonen; in beginsel centreren op huidige locatie. Als locatie ontbreekt of geweigerd wordt: laatst bekeken gebied en anders een bruikbare landelijke kaartweergave. Locatiebepaling mag de kaart niet blokkeren. NDW-first met bestaande ChargeWatch-fallback bij NDW-uitval.
- **Locatiemarkers tonen `vrij/passend totaal`**, bijvoorbeeld `2/4`. De aantallen gelden uitsluitend voor aansluitingen die aan de **actieve filters** voldoen. Afwijkende of onbekende status niet als bezet of vrij suggereren.
- **Clustering bij overlap:** clusters tonen óók uitsluitend `vrij/passend totaal` (bijvoorbeeld `5/12`), gesommeerd over onderliggende locaties, **niet het aantal locaties**. Cluster herkenbaar door een extra ring om dezelfde markeropmaak. Geen tweede getal, bijschrift of tekst onder markers. Bij aanraken verder inzoomen of overlappende locaties bereikbaar maken.
- **Kleurcodering:** groen als minstens één passende aansluiting beschikbaar is; rood als nul passende aansluitingen beschikbaar zijn en de statussen bekend zijn; grijs/waarschuwing bij onbekende of onvoldoende betrouwbare data. Voor een gedeeltelijk onbekende telling moet het ontwerp expliciet tonen dat de ratio niet volledig betrouwbaar is; geen schijnzekerheid. Stale feed mag geen onvoorwaardelijke live-beschikbaarheid suggereren.
- **Favoriet op kaart:** individuele favoriet heeft uitsluitend een klein sterretje **rechtsboven op/in de marker**, zonder de ratio onleesbaar te maken. Clusters krijgen geen ster.
- **Kaartfilters op hoofdlijnen:** aansluitingstype, AC/DC, minimaal vermogen, beschikbaarheid; filters combineren en aantallen op marker/cluster volgen de selectie. Gebruiker wil deze filters; exacte opties, interactie en schema-/NDW-veldondersteuning worden nog gevalideerd. Geen kaartmarker-specifieke ChargeWatch-verrijkingsrequests. Locaties zonder passende aansluitingen worden niet getoond.
- **Favorieten blijven lokaal** op Android; app en widget hanteren dezelfde statusregels.

### Voorstellen — nog niet definitief goedgekeurd
- Bottom navigation met alleen **Kaart** en **Favorieten**; zoeken in de kaartzoekbalk; instellingen secundair.
- Zoekbalk en filterknop bovenaan, actieve filterchips, knop huidige locatie; selectie van losse marker opent compacte bottom sheet en daarna details.
- Eén scrollbare detailpagina met aansluitingen, tarieven, statistieken en dataversheid in plaats van aparte tabs.
- Compacte en uitgebreide favorietenwidget zonder mini-kaart.
- Filtervoorkeuren lokaal onthouden; precieze reset-/bewaarsemantiek nog te bespreken.

### Besluit — locatieafbeeldingen en externe acties (24 oktober 2026)
- **Geen locatieafbeeldingen in MVP**: geen automatische Street View-/Google Places-foto's in kaart-bottom-sheet of detailpagina, geen fotolaadverzoeken of nieuwe fotokosten.
- **Wel externe acties** op geselecteerde locatie en in details: **Navigeren** naar coördinaten via een geïnstalleerde navigatie-app en **Street View bekijken** via Google Maps (extern). Beschikbaarheid van Street View is niet gegarandeerd; geen ingebouwde panorama-/foto-API nodig.
- Visuele mock-ups zonder verplichte foto ontwerpen; compact detailoverzicht gebruikt die ruimte voor status en acties. Definitieve iconen en positionering nog te beoordelen.

### Visuele basis gekozen — kaart, detail en favorieten (24 oktober 2026)
De samen bekeken derde mock-up geldt **als uitgangspunt** voor verdere vormgeving, niet als definitieve pixel- of totaalontwerpgoedkeuring.

- **Kaart:** zoekbalk en filterchips bovenop de kaart, twee hoofditems Kaart/Favorieten, gekleurde beschikbaarheidsmarkers met `x/y`, clusterring en kleine favorietster rechtsboven. In de gegenereerde mock-up zijn sommige clusters abusievelijk blauw; **de afgesproken statuskleuren gelden ook voor clusters**. Blauwe markering is geen goedgekeurde aparte clusterstatus.
- **Geselecteerde locatie / bottom sheet:** niet kleiner maken; titel, adres, afstand, beschikbaarheid, passend vermogen en connectorsoorten, ster, externe acties Navigeren en Street View, doorklik naar details en een **mini-staafgrafiek met historische bezetting per uur**. Geen foto.
- **Detailpagina:** helder EVSE-/aansluitingenoverzicht en tarieven; onder "Populaire laadtijden" een combinatie van **staafdiagram gemiddelde historische bezetting** en **lijn historische kans op minstens één vrije aansluiting** per gekozen weekdag/uur.
- **Favorietenlijst:** overzichtelijke statuskaarten inclusief **kleine historische bezettingsgrafiek** per favoriet, eigen namen en lokale opslag.
- **Statistische waarborgen:** alle getoonde grafiekwaarden in de conceptafbeeldingen zijn fictief. Alleen werkelijk beschikbare en gevalideerde data tonen; `availableProbability=null` betekent **geen percentage/lijn publiceren**, niet nul procent. Bij onvoldoende historische data tonen we een neutrale uitleg. "Populaire laadtijden" is een presentatietitel en mag niet suggereren dat echte laadsessies of nauwkeurige voorspellingen zijn gemeten. Geen ruwe historie opslaan.

Nog te ontwerpen/beoordelen: definitieve grafieklegenda en toegankelijkheid, correcte tel-eenheid EVSE versus connector en brondekking, narrow-screen geometrie, dark mode, interactieve flows, beide Glance-widgetformaten. Geen Android UI-code voor expliciet totaalontwerpakkoord.

### Open ontwerp-/contractpunten
1. Echte visuele kaartmock-up: schermhiërarchie, responsive afmetingen, marker-/ring-/stergeometrie, iconografie, kleurencontrast en light/dark.
2. Betekenis van de noemer: de bron onderscheidt locatie/EVSE/connector. Voorkom dubbeltelling bij meerdere connectoren per EVSE; definieer expliciet het telobject voor `x/y` en de weergave bij deels onbekende status.
3. Exacte NDW GeoJSON-dekking voor vermogen, connectorsoort en filters. Bij ontoereikende bronvelden functies beperken of transparant als onbekend behandelen, niet alle markers via de eigen API verrijken.
4. Overlap/clustering met meer dan 1.000 NDW-features per bbox, viewport-cache en NDW-quota; geen onbetrouwbare totalen in onvolledige clusters.
5. Locatiedetails, tarief-/statistiekpresentatie (bij `availableProbability=null` geen percentage), zoek- en favorietenflows; widgetlayouts en updatefrequentie.
6. Expliciete goedkeuring complete app- én widgetdesign vóór de eerste Android UI-code; documentatie-PR en merge hebben apart akkoord nodig.

## Plaats in de planning (besloten)

1. **Laatste backendfeature (PR #33 gemerged)**: conservatieve NDW GeoJSON-feature-ID parsing en gerichte read-only ChargeWatch-detail/statistiekenlookup op de bestaande externe OCPI-sleutel; tests en statusupdate in dezelfde PR.
2. **Design/layout app én widget**: een zelfstandige, gezamenlijke ontwerpfase. **Geen Android UI-implementatie voordat de gebruiker de ontwerpen expliciet heeft beoordeeld en goedgekeurd.** Dit is een afzonderlijke fase ná de backendstap.
3. **Android MVP**: Kotlin/Jetpack Compose; kaart rechtstreeks via NDW, ChargeWatch-details pas bij openen en ChargeWatch-kaartfallback bij NDW-uitval.
4. **Widget**: Glance-weergaven op basis van goedgekeurde widgetontwerpen en lokaal bewaarde favorieten.
5. **Publieke uitrol**: HTTPS, server-side Play Integrity-verificatie, correcte proxy-IP-behandeling, per-IP en capaciteitslimieten, release-/veiligheidstests. Dit mag pas publiek worden wanneer de afgesproken releasegates slagen.

De voorgestelde nummers zijn planning, geen vooraf aangemaakte PR's of definitieve sprintomvang.

## Designfase: scope en opleveringen

- **Use-cases en informatiestructuur**: kaart, zoek-/filtermogelijkheden, locatie-details, favorieten, laadpaalstatussen, navigatie van kaart naar detail en van widget naar app.
- **Gebruikersstromen en schermarchitectuur**: wat de gebruiker direct op de kaart moet kunnen zien versus pas in details; lege, laad-, fout- en offline-/fallbacktoestanden; toestelgroottes en oriëntatie.
- **Visuele ontwerpen**: concrete high-fidelity mock-ups/prototypes van kernschermen en app-widgetcombinaties; ten minste een compacte en een grotere widgetvariant, met aandacht voor Android resize- en refreshgedrag.
- **Gedeelde ontwerpregels**: kleurgebruik, semantische beschikbaarheidsstatussen, iconen, typografie, contrast/toegankelijkheid, light/dark mode, touch targets en consistentie tussen app en widget.
- **Inhoud en datacontract**: NDW GeoJSON voor kaartstatussen, serververrijking alleen bij details, locatie-identiteit op gecontroleerde externe OCPI-key, lokale favorieten; onderscheid tussen beschikbaar, laden/bezet, gereserveerd/defect/onbekend; nooit `total - available` automatisch als 'bezet' labelen.
- **Beoordeling**: samen opties/varianten bekijken, keuzes en open punten noteren; gebruiker geeft **expliciet ontwerpakkoord** vóór UI-code. Een PR met ontwerpbestanden en statusoverdracht wordt eveneens pas na expliciet merge-akkoord gemerged.

## Ontwerpprincipes / randvoorwaarden

- De kaart moet snel en goed scanbaar blijven; **geen onnodige ChargeWatch-verrijkingsrequests per kaartmarker**.
- Widget en app delen identiteit/statussemantiek en herkenbare visuele taal; ontwerp beide in dezelfde fase, implementeer stapsgewijs.
- Favorieten, namen en volgorde blijven uitsluitend lokaal op het toestel; geen gebruikersaccounts of server-side favorietendatabase voor MVP.
- De NDW 10 req/s-limiet is gedocumenteerd; toewijzing per publiek IP is **niet bevestigd**. Ontwerpdebounce, viewport-caching en 429/fallback-verwerking; deelquota door NAT/CGNAT blijft aandachtspunt.
- Statusvoorspellingen zijn geen harde realtime garanties; toon versheid/onzekerheid waar relevant.
- Geen redesign van de serverarchitectuur tijdens de designfase zonder apart besluit.

## Definition of done — designfase

Een goedgekeurde set app- en widgetontwerpen, vastgelegde gebruikersstromen, semantische staten, basiscomponenten en prioriteit voor MVP versus later. Documentatie (dit bestand en `docs/STATUS.md`) wordt bijgewerkt in dezelfde PR als het afgeronde ontwerp. Pas dan Android UI-featurebranches starten.

## Huidige vervolgactie

Kaartscherm als geheel visueel uitwerken en bespreken; daarna locatiedetails, favorieten en beide widgetformaten. Besluiten iteratief hier vastleggen. Android UI-code blijft geblokkeerd tot expliciet totaalontwerpakkoord.
