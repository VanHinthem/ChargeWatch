# Issue #103 — historische statistieken: vastgesteld grafiekontwerp

**Besluiten:** 6 november 2026 · **Status:** historisch functioneel ontwerp, op Android uitgevoerd en door gebruiker op Samsung geaccepteerd in PR #131. Bepaalde oorspronkelijk voorgestelde rekenvalidaties en 90-daagse uptime/publicatiecriteria blijven vervolgwerk in issue #103.  
**Issue:** [#103](https://github.com/VanHinthem/ChargeWatch/issues/103)

> **Historisch ontwerpdocument.** De UI is inmiddels geïmplementeerd via PR #131 met hetzelfde bestaande statistiekendpoint en een 15-minuten-cache. De goedgekeurde Android-verfijningen zijn: contrastrijk oranje per thema, standaard huidige NL-dag/uur, uurselectie met donkergroene balk en subtiele celachtergrond (zonder verticale lijnen), geen zichtbare uurvakdekkingen, compacte EVSE-rijen en UNKNOWN als aparte categorie. De inhoud hieronder beschrijft tevens historische ontwerpkeuzes en NAS-read-only testvoorbeelden; voor resterend werk zie issue #103.

## Bestaande situatie ten tijde van het ontwerp

De uitgebreide Android-locatiesheet toont nu een grafiek voor de historische, per Amsterdamse weekdag en uur berekende indicatie dat **minimaal één EVSE op de locatie vrij is**. De bestaande API levert `availableProbability` uit de locatiecategorie-EWMA, niet uit een vermenigvuldiging van losse EVSE-kansen. De endpoint markeert deze waarden nog als `isExperimental=true` / `UNVALIDATED_SNAPSHOT_EWMA`.

De actuele `LocationStatistics.Slots` bevatten al `averageAvailableEvses`, `averageActiveEvses`, `availableEvseSampleCount`, `excludedEvseSampleCount`, `sampleCount`, `changedFeedCount`, `unchangedFeedCount` en `lastSampleAt`. Het bestaande Android-model `HourlyAvailability` leest momenteel slechts percentage en sampleCount. Voor de uitbreiding is in beginsel geen nieuwe statistiekberekening of databasekolom nodig, maar de correcte uitlezing en datakwaliteit moeten vooraf worden gecontroleerd.

## Vastgesteld UX-ontwerp: één gecombineerde grafiek

1. **Eén grafiek in de uitgebreide locatiesheet.** Vervang de enkelvoudige beschikbaarheidsgrafiek door een combinatie van:
   - **Groene balken:** historische beschikbaarheidsindicatie in procenten — er was minstens één EVSE vrij.
   - **Oranje lijn:** historisch gemiddeld aantal vrije EVSE's per uurvak.
2. **De bestaande weekdagselectie (ma–zo) blijft.** Per geselecteerde dag worden 24 lokale uurvakken getoond.
3. **Eén gedeelde uurselectie.** Bij selectie van een balk/uur verschijnen voor dat uur in dezelfde grafieksectie:
   - beschikbaarheidsindicatie (%);
   - gemiddeld aantal vrije EVSE's, in Nederlandse decimaalnotatie;
   - eventueel gemiddeld aantal actieve EVSE's als compacte contextregel, *niet* als aparte grafiek.
4. **Twee expliciet gelabelde schalen:** links 0–100% voor de balken; rechts aantallen EVSE's voor de lijn. De rechter schaal volgt de capaciteit/werkelijke range van de locatie en is niet hardcoded op zes; visuele assen mogen niet suggereren dat percentage en aantallen dezelfde eenheid hebben.
5. **Geen extra kaarten of aparte grafiek** voor het gemiddelde aantal vrije EVSE's. De huidige compacte sheet, actuele bezetting, filters en favorieten blijven onveranderd.
6. **Geen 90-dagen-wachttijd en geen melding** dat nog geen 90 dagen meetgegevens beschikbaar zijn. Toon de bestaande geldige historie zoals opgeslagen; de EWMA-halfwaardetijd van 90 dagen is geen minimaal vereist meetvenster.
7. **Geen fictieve waarde bij ontbrekende data:** ontbrekende/ongeldige uurvakken blijven leeg of krijgen 'Geen gegevens', en tellen niet als 0%. Percentage en gemiddeld aantal vrije EVSE's kunnen onafhankelijk van elkaar ontbreken; toon dan alleen de aanwezige maat. Geen interpolatie of stilzwijgende nul.
8. De grafiek beschrijft **historische NDW-waarnemingen, geen gegarandeerde voorspelling**. Voeg geen nadrukkelijke waarschuwing over de lengte van de meetgeschiedenis toe. Verander het bestaande experimentele API-label of de publicatiecriteria niet stilzwijgend; bespreek dat afzonderlijk voordat definitieve betrouwbaarheidsclaims worden gedaan.

## Databron en kwaliteitscontrole vóór implementatie

- De procentwaarde komt uit de **locatie**-categorie `AnyAvailable` via `availableProbability`. Niet optellen of combineren uit individuele EVSE-percentages.
- De vrije-aantallenlijn komt uit `averageAvailableEvses` en gebruikt de bijbehorende geldigheidsdekking (`availableEvseSampleCount`, `excludedEvseSampleCount`). Controleer of de count-historie voor gekozen echte locaties voldoende gevuld is; deze kolommen zijn later toegevoegd dan de bestaande kansindicatie.
- Het gemiddeld aantal actieve EVSE's is `averageActiveEvses`; dit is historische actieve capaciteit, niet de gegarandeerde actuele aansluitingsteller.
- De 168 slots zijn weekdag × lokaal uur. Een sampleCount van 24 betekent 24 waarnemingen, **niet** 24 onafhankelijke weken.
- De bestaande snapshotprofielen zijn geen betrouwbare rollende daghistorie van exact 90 dagen. Een eventueel 90-daags EVSE-uptimepercentage is een **apart ontwerp en een aparte beslissing**, geen onderdeel van deze grafiekwijziging.

## Geverifieerde NAS-waarnemingen (6 november 2026, read-only)

- Laatste geaccepteerde NDW-poll: `2026-11-06 10:34:12.472156+00`; `accepted_poll_count=3522`; actieve uuraccumulaties: 82.807 locaties en 208.163 EVSE's, allemaal voor `2026-11-06 10:00:00+00`.
- Eerste steekproef: 200 profielrijen / 4.800 uurvakken; 4.690 met samples en gewijzigde feed.
- Tweede steekproef: eerste 500 actieve locatie-ID's; 497 volledig gevulde 168-vaks profielen en alle 500 met zeven weekdagen.
- Derde controle op dezelfde 500 locaties: 84.000 slots, 20 zonder samples; mediaan 24, p95 24, maximaal 36 samples; 100% van samples geclassificeerd als changed feed.
- Deze metingen zijn **geen aselecte landelijke representativiteitsmeting** en bewijzen geen onafhankelijke kalenderweekdekking. Ze rechtvaardigen niet zelfstandig een hard nieuw betrouwbaarheidscijfer.

## Vastgesteld UX-ontwerp: historische storing en betrouwbaarheid (6 november 2026)

**Gekozen productrichting: optie A — betrouwbaarheid van de afzonderlijke EVSE's**, niet slechts de vraag of een locatie minimaal één operationele EVSE heeft. De sectie volgt onder de gecombineerde beschikbaarheidsgrafiek en blijft standaard compact.

1. **Locatiesamenvatting:** toon een compacte historische indicatie voor `Operationeel` versus `Storing`, bij voorkeur met groen/rode horizontale verhouding. Geen tweede grote tijdgrafiek.
2. **Uitklapbaar paneel:** één regel `Betrouwbaarheid per EVSE (N)`, standaard ingeklapt. Uitklappen toont de EVSE's met een herkenbaar label (zoals connectorsoort/vermogen waar de catalogus die betrouwbaar levert), een operationele indicatie en een compacte horizontale statusbalk.
3. **Detail per EVSE:** individuele EVSE kan verder worden uitgeklapt voor de delen `Operationeel`, `Storing` en `Onbekend`. Bij ontbrekende gegevens staat `Geen gegevens` in plaats van een fictieve 0%.
4. **Betekenis van statussen:** `AVAILABLE`, `CHARGING`, `RESERVED` en `BLOCKED` zijn operationeel; `INOPERATIVE` en `OUTOFORDER` zijn storing; `UNKNOWN` blijft afzonderlijk onzeker; `PLANNED` en `REMOVED` vallen buiten de actuele actieve capaciteit. Bezet is niet defect.
5. **Aggregatie zonder schijnprecisie:** locatiecijfer is *niet* een simpel gemiddelde van EVSE-percentages. De definitieve berekening moet dekking en juiste waarnemingsgewichten volgen. De som van percentages mag niet kunstmatig 100% suggereren zolang `UNKNOWN` in de berekeningsbasis voorkomt. Dit zijn expliciet nog te valideren rekenregels, geen toestemming voor nieuwe migraties.
6. **Historische indicatie, geen bewezen uptime:** statuscategorieën zijn al als compacte EVSE-EWMA per weekdag/uur opgeslagen. Hieruit volgt nog **geen exacte uptime van de afgelopen 90 kalenderdagen**. De UI mag de bestaande indicatie niet onder het label `90-daagse uptime` publiceren.
7. **Geen waarschuwing over onvoldoende 90-daagse historie:** toon aanwezige valide historische gegevens zonder automatisch een melding over de lengte van de historie te plaatsen. Onbekende of ongeldige cijfers blijven expliciet leeg.

### Technisch rekenvoorstel — historische betrouwbaarheid (6 november 2026)

De berekening gebruikt de **bestaande negen afzonderlijke EWMA-statuscategorieën per EVSE, weekdag en lokaal uur** uit `evse_snapshot_weekday_profile.category_ewma`. De huidige API `EvseStatistics.Slots` publiceert alleen `availableProbability` (de eerste categorie); voor een storingsweergave moet de API deze overige categorieën eerst expliciet aanbieden of een gecontroleerde, afgeleide betrouwbaarheidsrepresentatie retourneren. De huidige Android-client ontvangt ze nog niet.

**Categorieën per uurvak:**

- `operational` = AVAILABLE + CHARGING + RESERVED + BLOCKED (categorie-indexen 1–4).
- `fault` = INOPERATIVE + OUTOFORDER (indexen 5–6).
- `unknown` = UNKNOWN (index 7).
- PLANNED en REMOVED (indexen 8–9) worden buiten de actieve observatiebasis gehouden.
- Alleen uurvakken met `sample_count > 0`, eindige categorieën binnen [0,1] en een consistente EWMA-statusverdeling zijn geldig. Controleer afrondingsfouten met kleine numerieke tolerantie; stel bij onverklaarbare inconsistentie het volledige uurvak op ontbrekend, niet op nul.

**Per EVSE:** combineer over de aanwezige weekdag-/uurvakken de geldige categorie-aandelen. Geef ieder daadwerkelijk gedekt uurvak hetzelfde gewicht bij een beschrijving van een *gemiddelde historische week*, in plaats van een hoger gewicht aan uren met meer afzonderlijke vijfminutenpolls. Sla ongedekte uurvakken over en rapporteer dekking afzonderlijk. Dit is een historische EWMA-indicatie, niet een exacte som van beschikbaarheidsduur.

**Voor de locatie:** pool de bijdragen van alle **actieve EVSE's** en alle geldige EVSE-uurvakken; niet het ongewogen gemiddelde van afgeronde individuele percentages of een vermenigvuldiging van EVSE-kansen. Iedere geldige EVSE-uurvak-combinatie draagt één gewicht bij. Laat EVSE's zonder bruikbare waarnemingen apart zichtbaar met `Geen gegevens`, en maak de noemer/dekking expliciet. De juiste behandeling van variabele capaciteit door de tijd is een testpunt; historische verwijderde EVSE's mogen niet ongemerkt als actuele capaciteit verschijnen.

**Percentagepresentatie (voorstel):** gebruik voor de compacte 3-delige statusverdeling `D = operational + fault + unknown` (de drie geaggregeerde aandelen over geldige EVSE-uurvakken):
- `operationeel % = 100 × operational / D`
- `storing % = 100 × fault / D`
- `onbekend % = 100 × unknown / D`

Zo blijft UNKNOWN zichtbaar in de totale 100%; een operationeel percentage kan niet kunstmatig stijgen doordat onbekende waarnemingen buiten de noemer verdwijnen. Verwar dit **niet** met `operationeel / (operationeel + storing)` onder uitsluitend bekende toestanden; toon dat laatste eventueel later onder een ander expliciet label. Rond in de UI af zonder een ontbrekende categorie te verzinnen. Bij `D = 0`, ontbrekende data of ongeldige waarden: geen percentage. De sectie toont eventueel een groen/rood/grijs balkje; het actuele vrij/totaal-kleurbeleid van de kaart is een **ander** concept en blijft ongewijzigd.

**Tijdscope en versheid:** deze samenvatting is het historische *weekprofiel over de tot dusver verzamelde meetperiode*; niet alleen het gekozen uur in de beschikbaarheidsgrafiek en **geen exacte rollende 90-daagse uptime**. De momentane NDW-status blijft elders in de sheet actueel. Geen waarschuwing over het ontbreken van 90 dagen meetgegevens.

**API-contract:** behoud bestaande `LocationStatistics`-velden en compatibiliteit; voeg afgeleide velden of een apart betrouwbaarheidssubobject voor de locatie en afzonderlijke EVSE's toe. Laat daarin operationeel/storing/onbekend, ontbrekendheid en gedekte uurvakken door de server berekenen, zodat Android geen uiteenlopende interpretaties ontwikkelt. De precieze JSON-vorm is nog implementatieontwerp en vraagt contracttests.

**Risicogerichte acceptatie:** bekende statusmix, bezet-maar-operationeel, defect-only, UNKNOWN-only, PLANNED/REMOVED-only, nieuwe EVSE met gedeeltelijke dekking, EVSE met weinig data, lege/onvolledige categoriearray, onverwachte bronwaarde, lokale DST-uurvakken, uiteenlopende EVSE-dekking en ontbreken van labels. Controleer daarna concrete NAS-voorbeelden read-only; geen mutatie of migratie. Dit zijn ontwerpregels; **geen geclaimde testresultaten**.

### NAS-validatie van storings- en onbekendpercentages (6 november 2026)

Read-only inspectie op de productie-NAS; alle getoonde EVSE's hadden 168 gevulde weekdag-/uurvakken. Dit zijn **EWMA-profielaandelen**, geen bewezen werkelijke uptime over 90 dagen.

| Situatie | EVSE | Locatie | Operationeel | Storing | Onbekend |
|---|---:|---:|---:|---:|---:|
| Alleen operationele status | 1–8 | 67363 | 100,0% | 0,0% | 0,0% |
| Gedeeltelijke storing | 3735 | 68839 | 0,1% | 96,4% | 3,5% |
| Gedeeltelijke storing | 3083 | 68612 | 0,0% | 95,1% | 4,9% |
| Gedeeltelijke storing | 3360 | 68674 | 0,0% | 92,6% | 7,4% |
| Alleen onbekend | 445, 446 | 67531 | 0,0% | 0,0% | 100,0% |
| Alleen onbekend | 546 | 67568 | 0,0% | 0,0% | 100,0% |
| Alleen defect | 348, 349 | 67495 | 0,0% | 100,0% | 0,0% |

De 100%-unknown voorbeelden tonen expliciet waarom `UNKNOWN` niet mag worden gelijkgesteld aan storing en waarom een bekend-toestandpercentage zonder kwaliteitscontext misleidend kan worden. Een 168/168 gevuld profiel bewijst op zichzelf niet dat statusinformatie ook betekenisvol bekend is.

**Validatiegrens:** geselecteerde voorbeelden uit maximaal de eerste 10.000 actieve EVSE's, geen landelijke statistische verificatie. De SQL gebruikt op EVSE-niveau gelijke weging per geldig weekdag-/uurvak en `operational + fault + unknown` als noemer. De voorbeelden ondersteunen het onderscheid van de categorieën; ze bewijzen nog **niet** de nauwkeurigheid van een samengevoegd locatiecijfer, de juiste noemer bij `PLANNED/REMOVED` of representatieve data over meerdere weken. Voor de API-implementatie zijn gerichte tests daarvoor nodig.

**UX-besluit voor 100% UNKNOWN:** toon `Onbekend` / `Geen betrouwbare statusinformatie` en niet een opvallende 0%-betrouwbaarheid of 100%-storingsclaim. De volledige statusverdeling kan worden getoond na uitklappen, met 100% onbekend.

### Nog te valideren vóór implementatie

- Hoe de bestaande EVSE-categorie-EWMA's met geldige categorieën, onzekerheid en uitgesloten lifecycle-staten worden omgezet in een operationeel-/storingsaandeel; vooral de **noemer**, weging van EVSE's, historische weekdagen/uren en minimumgeldigheid.
- Of het locatieoverzicht de historische indicatie moet samenvatten over alle 168 uurvakken of bewust een geselecteerde periode (bijvoorbeeld het uur in de gecombineerde grafiek) volgt. Voorkom onbedoeld mengen van tijdvakken met afwijkende dekking.
- Correcte koppeling van bestaande EVSE-ID's aan herkenbare connectorlabels, inclusief meerdere connectoren en onbekende vermogens.
- API-/Android-afhandeling voor nul samples, ongeldige EWMA, UNKNOWN, nieuwe EVSE, verwijderde EVSE, per-EVSE-fouten en incompleet locatiedeel.

**Niet besloten:** een extra 90-daagse rollende uptime-pipeline, nieuwe databasekolommen, een minimumaantal onafhankelijke weken of een gegarandeerd betrouwbaarheidspercentage. Het hierboven beschreven drie-categorieënmodel is een technisch voorstel voor de gekozen UX en moet vóór implementatie met echte EVSE-profielen worden gevalideerd.

## Review- en implementatiegrens (oorspronkelijke ontwerpfase)

Dit ontwerp is inmiddels opgedeeld in API-PR #130 (gemerged en door de gebruiker op NAS uitgerold; publieke response gecontroleerd) en Android-PR #131 (CI en Samsung UX-acceptatie geslaagd; merge na expliciet akkoord). De oorspronkelijke ontwerp-/validatiestappen hierboven zijn historisch en vervangen niet de huidige projectstatus in `docs/STATUS.md`. Exacte 90-daagse uptime en robuuste publicatiecriteria zijn **niet** alsnog bewezen of geïmplementeerd.
