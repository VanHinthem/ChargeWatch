# Issue #103 — historische statistieken: vastgesteld grafiekontwerp

**Besluiten:** 6 november 2026 · **Status:** functioneel ontwerp vastgesteld; geen implementatie of wijziging aan de NAS.  
**Issue:** [#103](https://github.com/VanHinthem/ChargeWatch/issues/103)

## Bestaande situatie

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

### Nog te valideren vóór implementatie

- Hoe de bestaande EVSE-categorie-EWMA's met geldige categorieën, onzekerheid en uitgesloten lifecycle-staten worden omgezet in een operationeel-/storingsaandeel; vooral de **noemer**, weging van EVSE's, historische weekdagen/uren en minimumgeldigheid.
- Of het locatieoverzicht de historische indicatie moet samenvatten over alle 168 uurvakken of bewust een geselecteerde periode (bijvoorbeeld het uur in de gecombineerde grafiek) volgt. Voorkom onbedoeld mengen van tijdvakken met afwijkende dekking.
- Correcte koppeling van bestaande EVSE-ID's aan herkenbare connectorlabels, inclusief meerdere connectoren en onbekende vermogens.
- API-/Android-afhandeling voor nul samples, ongeldige EWMA, UNKNOWN, nieuwe EVSE, verwijderde EVSE, per-EVSE-fouten en incompleet locatiedeel.

**Niet besloten:** een extra 90-daagse rollende uptime-pipeline, nieuwe databasekolommen, een minimumaantal onafhankelijke weken of een gegarandeerd betrouwbaarheidspercentage.

## Review- en implementatiegrens

Dit document legt de gekozen functionele ontwerpen voor de gecombineerde grafiek en de uitklapbare EVSE-betrouwbaarheid vast. Er is **nog geen code, migratie, API-wijziging, APK, Samsung-test of merge**. Implementatie van de gecombineerde grafiek en van betrouwbaarheid/storing volgen pas nadat ook dat tweede ontwerp is besproken en de scope is bevestigd. Geen merge of NAS-deployment zonder expliciet akkoord.
