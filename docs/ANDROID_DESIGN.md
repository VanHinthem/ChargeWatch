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

### Versiebeheerde visuele referentie

**Primaire visuele referentie (goedgekeurd als uitgangspunt, niet als definitief totaalontwerp):**

![ChargeWatch Android app — eerste vier-schermen mock-up](design/EV%20Charging%20Station%20App%20Mockup.png)

- Bronbestand: [EV Charging Station App Mockup.png](design/EV%20Charging%20Station%20App%20Mockup.png), toegevoegd op `docs/android-ux-design` binnen PR #35.
- De mock-up is leidend voor de algemene visuele richting van kaart, geselecteerde locatie, details en favorieten. **Expliciet besproken ontwerpbesluiten in dit document gaan vóór onjuiste of toevallige details in gegenereerde mock-ups** (zoals afwijkende clusterkleuren of fictieve cijfers).
- `docs/design/android-ux-baseline.svg` is slechts een schematische extra referentie; de PNG is de primaire visuele referentie.
- Alle in mock-ups getoonde gegevens, grafieken en tarieven zijn illustratief. De exacte styling, widgetvarianten en het volledige Android-ontwerp zijn nog niet definitief goedgekeurd.

### Visuele basis gekozen — kaart, detail en favorieten (24 oktober 2026)
De samen bekeken derde mock-up geldt **als uitgangspunt** voor verdere vormgeving, niet als definitieve pixel- of totaalontwerpgoedkeuring.

- **Kaart:** zoekbalk en filterchips bovenop de kaart, twee hoofditems Kaart/Favorieten, gekleurde beschikbaarheidsmarkers met `x/y`, clusterring en kleine favorietster rechtsboven. In de gegenereerde mock-up zijn sommige clusters abusievelijk blauw; **de afgesproken statuskleuren gelden ook voor clusters**. Blauwe markering is geen goedgekeurde aparte clusterstatus.
- **Geselecteerde locatie / bottom sheet:** niet kleiner maken; titel, adres, afstand, beschikbaarheid, passend vermogen en connectorsoorten, ster, externe acties Navigeren en Street View, doorklik naar details en een **mini-staafgrafiek met historische bezetting per uur**. Geen foto.
- **Detailpagina:** helder EVSE-/aansluitingenoverzicht en tarieven; onder "Populaire laadtijden" een combinatie van **staafdiagram gemiddelde historische bezetting** en **lijn historische kans op minstens één vrije aansluiting** per gekozen weekdag/uur.
- **Favorietenlijst:** overzichtelijke statuskaarten inclusief **kleine historische bezettingsgrafiek** per favoriet, eigen namen en lokale opslag.
- **Statistische waarborgen:** alle getoonde grafiekwaarden in de conceptafbeeldingen zijn fictief. Alleen werkelijk beschikbare en gevalideerde data tonen; `availableProbability=null` betekent **geen percentage/lijn publiceren**, niet nul procent. Bij onvoldoende historische data tonen we een neutrale uitleg. "Populaire laadtijden" is een presentatietitel en mag niet suggereren dat echte laadsessies of nauwkeurige voorspellingen zijn gemeten. Geen ruwe historie opslaan.

Nog te ontwerpen/beoordelen: definitieve grafieklegenda en toegankelijkheid, correcte tel-eenheid EVSE versus connector en brondekking, narrow-screen geometrie, dark mode, interactieve flows, beide Glance-widgetformaten. Geen Android UI-code voor expliciet totaalontwerpakkoord.

### Widgetmock-up — visuele referentie

![ChargeWatch Android widget showcase](design/ChargeWatch%20Android%20Widget%20Showcase.png)

- Primair widgetbeeld: [ChargeWatch Android Widget Showcase.png](design/ChargeWatch%20Android%20Widget%20Showcase.png), toegevoegd aan PR #35.
- De afbeelding toont compacte, overzichts- en uitgebreide varianten, statusweergaven en light/dark. Dit is een **voorlopig ontwerp**, geen finale configuratie of pixelgoedkeuring.
- De afgesproken widgetinteractie (tikken op locatie → Details/Navigeren) en optioneel aan/uitzetten van grafieken zijn leidend, ook als de afbeelding die acties nog niet laat zien.
- Alle aantallen/grafieken/tijden in de afbeelding zijn illustratief.

### Widget — voorlopige interactiebesluiten (24 oktober 2026)
- Compacte (2×2), overzichts- (4×2) en uitgebreide (4×3) Glance-widget zijn ontwerpvarianten; werkelijke resizable afmetingen testen op Android.
- **Historische minigrafieken per widget configureerbaar** (aan/uit); compact standaard uit, grotere varianten standaard aan als ruimte en gevalideerde gegevens dat toelaten. Beschikbaarheidsstatus heeft altijd prioriteit boven grafiek.
- Iedere widgetinstantie kan apart worden ingesteld (favorieten/selectie, grafieken en waar zinvol bijkomende gegevens), uitsluitend lokaal opgeslagen.
- **Tik op een locatie in de widget:** opent een klein actiemenu / bottom sheet in ChargeWatch met **Details** of **Navigeren**. Details opent de locatiepagina; Navigeren geeft coördinaten door aan een externe navigatie-app. Interactie werkt voor alle widgetformaten.
- Geen claims van continu live-verversen; toon bronversheid/laatste update en houd rekening met Android-achtergrondrestricties.
- Widgetmock-up is als PNG vastgelegd in de repository; dit is nog geen definitief totaalontwerpakkoord.

### Widgetconfiguratie — visuele referentie

![ChargeWatch Android Widget UX Board](design/ChargeWatch%20Android%20Widget%20UX%20Board.png)

- Primair configuratiebeeld: [ChargeWatch Android Widget UX Board.png](design/ChargeWatch%20Android%20Widget%20UX%20Board.png) in PR #35.
- De mock-up dient als **visuele richting**, niet als exacte implementatiespecificatie. De afgebeelde meerstappenwizard wordt **niet** overgenomen: het goedgekeurde ontwerp gebruikt **één scrollbaar configuratiescherm met live preview**.
- Widgetinstellingen worden per widgetinstantie lokaal opgeslagen; beschikbare ruimte en gegevenskwaliteit bepalen welke onderdelen kunnen worden getoond.
- De afgebeelde waarden zijn fictief.

### Widgetconfiguratie — gekozen ontwerprichting (24 oktober 2026)

- **Eén scrollbaar configuratiescherm**, géén verplichte wizard met vijf stappen.
- **Live widgetvoorbeeld bovenaan** dat de keuzes voor de betreffende widgetinstantie weerspiegelt.
- Daaronder: selectie van favoriete locatie(s) en volgorde, schakelaar voor historische mini-grafieken, optionele afstand en laatste-updateweergave. De widgetafmetingen bepalen welke inhoud past; beschikbaarheid gaat altijd vóór een grafiek.
- **Elke widgetinstantie heeft eigen lokale instellingen**, die ook later opnieuw aanpasbaar zijn.
- Android bepaalt de widgetplaatsing en resize; geen belofte dat alle formaten op elk toestel identiek zijn.
- Tik op een locatie opent ChargeWatch met een compacte keuze **Details / Navigeren**.
- De samen bekeken configuratie-mock-up is als **richting** geaccepteerd, niet als pixel-perfect definitief ontwerp. De mock-up toont deels een vijfstappenwizard die bewust **niet** wordt overgenomen. De PNG-mock-up is opgenomen in PR #35 en hierboven gelinkt.

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

## Designsystem — gekozen visuele richting (24 oktober 2026)

De onderstaande stijlrichting is door de gebruiker als basis bevestigd. De hex-kleuren zijn **startwaarden**; definitieve contrast-, leesbaarheids- en dark-modecontrole kan tot verfijning leiden zonder statusbetekenis te veranderen.

| Rol | Conceptkleur | Gebruik |
|---|---|---|
| Primair / beschikbaar | `#168653` | merkaccent, minstens één passende vrije aansluiting |
| Geen passende vrije aansluiting | `#D43B3B` | alleen bij betrouwbaar vastgestelde 0 vrij |
| Onbekend / onzekere status | `#818D96` | onbekend of onvoldoende betrouwbare beschikbaarheid |
| Historische bezetting | `#278DA7` | staafgrafieken; geen alternatieve clusterkleur |
| Favoriet | `#F3AF25` | ster rechtsboven op individuele marker |
| Licht oppervlak | `#F5F7F8` | subtiele kaart-/widgetachtergrond en panelen |
| Donker oppervlak | concept `#151D24`, kaarten `#26313A` | donkerblauw/antraciet in plaats van volledig zwart |

- **Typografie:** Android Roboto met Material 3-teksthiërarchie. Kerninformatie (zoals `2/4`) moet direct scanbaar zijn; geen overmatige hoeveelheid tekst of onleesbare labels in markers/widgets.
- **Iconen:** één consistente Material-stijl voor zoeken, filters, locatie, navigeren, Street View, details, favoriet, refresh en informatie.
- **Light/dark:** beide vanaf MVP; standaard Android-systeeminstelling volgen, gebruiker kan licht/donker expliciet kiezen. Dezelfde semantische statussen in beide thema's; controle op contrast en tinten.
- **Grafieken:** turquoise/blauwe staven voor historisch bezettingspatroon; groene lijn voor gevalideerde historische kans op minstens één vrije aansluiting. Duidelijke legenda; bij ontbrekende of niet-publiceerbare kans géén lijn/percentage tekenen.
- **Componenten:** afgeronde panelen, zachte vlakken en spaarzame schaduw. Kaart zelf houdt visueel de hoofdrol; compacte locatiekaart en widget gebruiken hetzelfde componentvocabulaire.
- **Toegankelijkheid:** kleur nooit als enige indicator, hoge leesbaarheid en tekstcontrast, voldoende grote bedieningsvlakken (richtlijn 48 dp voor interactieve doelen), grotere systeemfonts ondersteunen en niet steunen op tiny labels.
- **Statusintegriteit:** stale data apart zichtbaar maken; `0/y` is niet gelijk aan 'allemaal actief aan het laden'; onbekende metingen nooit tot rood of groen forceren. Bij cluster en individuele marker blijven statuskleuren identiek en onderscheidt alleen de ring het cluster.

**Nog geen totaalontwerpakkoord.** De resterende designreview omvat kaart-/widgetflows, betrouwbaarheid van `x/y` en grafieken, lege/loading/fallbackscenario's, toetsen van toegankelijkheid en referenties naar de mock-ups.

## Finale UX-contractreview — randgevallen en acceptatie (24 oktober 2026)

### Aantallen in markers en clusters
- `x/y` betekent **vrije / totale passende onafhankelijk bruikbare laadpunten** volgens de actieve filters; dit is een **doelsemantiek** en geen onbewezen gelijkstelling van NDW-connectortotalen aan unieke EVSE's.
- Eén EVSE met meerdere connectoren mag niet als twee gelijktijdig te gebruiken laadplekken worden opgeteld. De NDW GeoJSON `availabilities` bevat `available`, `total`, `connector_type`, `power_type` en `power_max`, maar de onderzochte GeoJSON levert **geen unieke EVSE-ID per connectorrij**. Daarom is unieke deduplicatie bij gecombineerde filters niet zonder meer mogelijk.
- **Implementatiegate:** eerst op echte NDW-fixtures toetsen of `available/total` veilig per gefilterde laadmogelijkheid kan worden geaggregeerd. Als betrouwbare deduplicatie niet mogelijk is: geen som over overlappende connectorcategorieën presenteren alsof het unieke EVSE's zijn; een neutrale samenvatting of beperking van filters gebruiken. Geen backendverrijking per marker als workaround.
- Clusters tellen uitsluitend **volledig geladen, werkelijk aanwezige kaartlocaties** op en krijgen de bestaande statuskleuren plus ring. Niet aggregeren over afgekapt NDW-resultaat (1000-featuresgrens), gemiste viewporttegels of dubbele locaties. Bij onvolledige data geen misleidend clustergetal.
- `0/y` kan defect, geblokkeerd, bezet of andere niet-beschikbare status bevatten en wordt nooit vertaald naar `y bezet`. `x/y` bij deels onbekende status vereist expliciete onzekerheidsindicatie of een neutrale onbekendstatus. Verouderde gegevens apart markeren.

### Lege, laad-, fout- en fallbacktoestanden
| Situatie | Ontwerpgedrag |
|---|---|
| Eerste start zonder locatiepermissie | Kaart meteen tonen; laatst bekeken regio, anders landelijke neutrale startkaart. Zoekfunctie en handmatige kaartbediening blijven werken. |
| Kaartverzoek in uitvoering | Subtiele laadstatus; eerder getoonde kaart/markers niet onnodig laten verdwijnen. |
| NDW timeout, 5xx, 429 of onbruikbare data | Beperkte retry/backoff en ChargeWatch-fallback; gegevensbron/versheid subtiel maar herkenbaar tonen. Geen eindeloze verzoeken. |
| Geen netwerk en wel cached data | Laatst bekende informatie met expliciete offline-/ouderdomsaanduiding; geen claim van actuele vrije plaatsen. |
| Geen netwerk en geen cache | Rustige fouttoestand met herproberen en toegang tot lokale favorieten. |
| Geen resultaten binnen filters | Duidelijke leegmelding met actie **Filters wissen**. |
| Geen favorieten | Korte uitleg met **Zoek een laadlocatie**; geen lege grafiekkaarten. |
| Widget zonder geselecteerde favorieten | Compacte instructie om widget te configureren; geen dummywaarden. |
| Widget te klein voor gekozen opties | Prioriteit: locatie-identiteit → beschikbaarheid en onzekerheid → versheid → acties → optionele grafiek. |
| Geen / onvoldoende historische data | **Geen verzonnen staven, kansen of percentages**; neutrale tekst 'Nog onvoldoende historische gegevens'. |
| Statistiek-API geeft `availableProbability=null` | Geen beschikbare-kanslijn/percentage; historische bezetting alleen als de gebruikte bronstatistiek voldoende valide is. |
| Externe Street View niet beschikbaar | Externe app kan zelf fallback tonen; ChargeWatch garandeert geen panorama en haalt geen foto's op. |

### Flows en schermpresentatie
- **Kaart → selectie → bottom sheet → details**; eventuele meerdere op elkaar liggende locaties via zoom of selectielijst bereikbaar.
- **Widget → tik op favoriete locatie → ChargeWatch-actiesheet Details / Navigeren**; juiste locatie behouden ook als actuele data ontbreekt. Widget is een weergave van lokaal gekozen favorieten, geen afzonderlijk cloudprofiel.
- Widgetconfiguratie is één scrollbaar scherm met live voorbeeld; per widgetinstantie bewaren. Historische grafieken mogen uit bij alle afmetingen en worden bij ruimtegebrek automatisch weggelaten.
- **Versheid:** onderscheid bronwijziging van landelijke feed-poll. Geen individuele EVSE-herbevestiging claimen op basis van `feedLastObservedAt`.
- **Tarieven:** actuele OCPI-componenten met bron-/contractnuance, nooit een gegarandeerd eindbedrag of btw-inclusieve consumentenprijs suggereren zonder betrouwbare grondslag.
- **Toegankelijkheid:** de app behoudt statusbetekenis in light/dark, grote tekst, TalkBack-omschrijvingen en voldoende aanraakruimte; kleuren zijn nooit de enige informatiedrager.

### Reviewstatus en implementatiegrenzen
De kaart-, app-, widget- en designrichting is interactief besproken. Deze review legt de randgevallen en expliciete data-acceptatiegates vast; **de volledige designfase is nog niet expliciet finaal goedgekeurd**. De drie PNG-mock-ups zijn visuele conceptreferenties en hun fictieve waarden zijn niet leidend boven het data- en beveiligingscontract. Geen Android UI-code, publieke API-uitrol of merge zonder apart akkoord.

### Appicoon — visuele referentie

![ChargeWatch concept 2 — Stekker en statusring](design/ChargeWatch%20App%20Icon%20Guideline%20Board.png)

- Bronbestand: [ChargeWatch App Icon Guideline Board.png](design/ChargeWatch%20App%20Icon%20Guideline%20Board.png) in PR #35.
- Deze PNG is de **primaire visuele referentie** voor het geselecteerde concept 2 (stekker + statusring), met donkerblauwe standaardvariant, monochrome variant en adaptive-iconvoorbeelden.
- De PNG is een ontwerpplaat, **niet** een set direct te gebruiken Android-resources. Foreground, background en monochrome vector-/bitmap-assets en veilige marges volgen bij de Android-implementatie.
- De gebruiker heeft de afzonderlijke uitwerking van **concept 2 expliciet goedgekeurd op 24 oktober 2026**. De PNG is definitieve visuele icoonreferentie; productie-assets worden tijdens Android-implementatie gemaakt.

## Appicoon — gekozen concept (24 oktober 2026)

De gebruiker heeft uit drie voorgestelde icoonrichtingen **concept B: stekker + statusring** gekozen als voorkeursrichting. Dit is een ontwerpskeuze binnen PR #35, de gekozen concept-2-ontwerpuitwerking is expliciet goedgekeurd; exportbestanden volgen bij implementatie.

- Donkerblauw/antraciet achtergrond, witte stekker, klein groen bliksemsymbool en groene (decoratieve) statusring.
- Statusring staat voor monitoring; suggereert **geen actuele operationele status**.
- Uit te werken: scherp vereenvoudigd icoon dat op launcherformaat leesbaar blijft, Android adaptive-icon foreground/background, monochrome versie voor themed icons, veilige marges en rond-masker-preview.
- De afzonderlijke concept-2-ontwerpplaat is als PNG aan de designbranch toegevoegd en hierboven gelinkt.
- Geen Android UI-/launcher-code of merge/deployment voor afzonderlijk akkoord.

## Definition of done — designfase

Een goedgekeurde set app- en widgetontwerpen, vastgelegde gebruikersstromen, semantische staten, basiscomponenten en prioriteit voor MVP versus later. Documentatie (dit bestand en `docs/STATUS.md`) wordt bijgewerkt in dezelfde PR als het afgeronde ontwerp. Pas dan Android UI-featurebranches starten.

## Huidige vervolgactie

Gezamenlijke finale designreview: vastgelegde flows, drie PNG-mock-ups, designsysteem en contractgates beoordelen; expliciet akkoord op het totale app- en widgetontwerp vragen. Daarna afzonderlijk PR-mergeakkoord en pas vervolgens Android UI-implementatie op een nieuwe featurebranch.
