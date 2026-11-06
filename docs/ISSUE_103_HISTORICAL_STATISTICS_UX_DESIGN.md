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

## Nog te ontwerpen — apart onderdeel binnen #103

**Historische storing/betrouwbaarheid**: bepaal eerst de betekenis en presentatie van EVSE-categorieën (`INOPERATIVE`, `OUTOFORDER` versus `CHARGING`, `BLOCKED`, `RESERVED`, `UNKNOWN`, `PLANNED`, `REMOVED`). De bestaande profieltabellen bewaren negen EVSE-categorieën en vijf locatiecategorieën als EWMA; een percentage feitelijke uptime over de afgelopen 90 kalenderdagen volgt hier **niet rechtstreeks** uit. Leg voor een betrouwbaarheidsindicatie eerst noemer, onbekende status, dekking en UI-label vast.

## Review- en implementatiegrens

Dit document legt uitsluitend het gekozen functionele grafiekontwerp vast. Er is **nog geen code, migratie, API-wijziging, APK, Samsung-test of merge**. Implementatie van de gecombineerde grafiek en van betrouwbaarheid/storing volgen pas nadat ook dat tweede ontwerp is besproken en de scope is bevestigd. Geen merge of NAS-deployment zonder expliciet akkoord.
