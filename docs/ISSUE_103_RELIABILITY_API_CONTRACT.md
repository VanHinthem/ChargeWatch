# Issue #103 — historische betrouwbaarheid in de read-only API

**Scope:** API-contract en berekening uit **bestaande** EVSE-snapshotprofielen. Geen migratie, importerwijziging, NAS-deployment of Android-UI.  
**Functionele UX-afspraken:** [geaccepteerd ontwerp](ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md), geïmplementeerd in [Android-PR #131](https://github.com/VanHinthem/ChargeWatch/pull/131); API-PR #130 is gemerged en op NAS geverifieerd.

**V1-besluit optie A (6 november 2026):** historische weekdag-/uur-EWMA en betrouwbaarheid worden als *experimentele indicaties* getoond, zonder claim van gekalibreerde beschikbaarheidskans of daadwerkelijk gemeten 90-daagse uptime. #103 is hiermee als v1-onderdeel afgerond. Warm-up-bias, publicatiecriteria en eventueel exacte rolling-90-day-uptime staan apart in [issue #132](https://github.com/VanHinthem/ChargeWatch/issues/132), niet blokkerend voor v1.

## Contract (additief)

De bestaande `GET /api/v1/locations/{id}/statistics`, de NDW-ID-variant en de optionele `statistics` in de batchrespons behouden `locationId`, `slots`, `evses`, `isExperimental` en `probabilityInterpretation` zoals voorheen.

Nieuw:

- `reliability` op het statistiekobject voor de hele locatie.
- `reliability` bij iedere actieve `evses[]`-entry.
- Elk object bevat `operationalPercent`, `faultPercent`, `unknownPercent` (allemaal nullable), `coveredSlots` en `totalSlots`.
- EVSE's zonder afgerond profiel worden met een lege `slots`-lijst en drie null-percentages gepubliceerd; geen impliciete 0%-betrouwbaarheid.
- `totalSlots = 168` per actieve EVSE (7 × 24), op locatieniveau het aantal actieve EVSE's × 168. `coveredSlots` telt geldige EVSE-uurvakbijdragen, niet onafhankelijke weken.
- Bestaande historische beschikbaarheid blijft `UNVALIDATED_SNAPSHOT_EWMA`. Ook betrouwbaarheid is een **historische indicatie**, geen exact gemeten 90-daagse uptime.

## Interpretatie

Bij ieder geldig weekdag-/uurvak zijn er negen cumulatief genormaliseerde EWMA-categorieën:

1. Operationeel = AVAILABLE + CHARGING + BLOCKED + RESERVED.
2. Storing = INOPERATIVE + OUTOFORDER.
3. Onbekend = UNKNOWN.
4. PLANNED/REMOVED uitgesloten van de actieve betrouwbaarheidsnoemer.

Ontbrekende slots, nul samples, niet-eindige waarden, ongeldige percentages, categoriearrays met onjuiste lengte of niet tot ongeveer 100% optellende statusverdelingen dragen **geen** observatiewaarde bij. Een uitsluitend PLANNED/REMOVED uurvak draagt evenmin bij. Een UNKNOWN-only slot blijft wel gedekt, met 100% `unknownPercent`.

Ieder geldig EVSE-uurvak draagt **één** gewicht bij aan een gemiddelde historische week. Een uur met twaalf vijfminutenpolls weegt niet zwaarder dan een uur met één geldige waarneming. Bij een uurvak met gedeeltelijk niet-actieve statussen worden de drie actieve categorieën binnen het uurvak op hun onderlinge verhouding genormaliseerd. Sommeer de actieve aandeelbijdragen over alle geldige EVSE-uurvakken; deel daarna in één stap door de totale actieve massa. De individuele en locatiepercentages worden op één decimaal afgerond; afrondingsverschillen van 0,1 procentpunt zijn mogelijk.

De locatie combineert de **onderliggende geldige EVSE-uurvakbijdragen**, niet een simpel gemiddelde van afgeronde EVSE-percentages. EVSE's zonder historie beïnvloeden de dekkingsnoemer, niet de percentages.

## Relevante randgevallen

- Een EVSE met 100% UNKNOWN resulteert in `operationalPercent=0`, `faultPercent=0`, `unknownPercent=100`. Android moet dit functioneel als 'Onbekend' presenteren, niet als defect of '0% betrouwbare EVSE'.
- Een EVSE zonder geldige dekking levert drie `null`-percentages op.
- Bezet/gereserveerd/geblokkeerd is operationeel maar niet vrij.
- Een nieuw aangemaakte EVSE met nul profielen moet in `evses[]` blijven staan.
- Oude of inactieve EVSE's worden niet gebruikt in actuele locatiecijfers; de API geeft momenteel alleen actieve EVSE's terug.
- De data is niet gekalibreerd als garantie of exacte uptime, en er komt geen extra waarschuwing over 'nog geen 90 dagen meetgegevens' in de UI.

## Validatie en uitrolgrens

Het API `--self-test`-pad bevat pure tests voor operationeel vs bezet, gemengd defect/UNKNOWN, UNKNOWN-only, PLANNED-only, geen samples, ongeldige categoriesom en gelijke weging per uur ondanks verschillend poll-aantal.

De PR gebruikt de bestaande read-only API GitHub Actions build/selftest en databasecontracttest. Een actuele NAS-/publieke HTTP-controle is **nog niet uitgevoerd**; daarvoor is na expliciet merge-/deploy-akkoord pas de gedeployde code nodig. Deze API-wijziging vraagt geen schema- of importerwijzigingen.

**Volgende afzonderlijke stap:** Android gecombineerde grafiek plus EVSE-accordion; gebruik de reeds bestaande beschikbaarheidsdata, count-metrics en de nieuwe betrouwbaarheidssamenvatting. Geen merge of deployment zonder expliciet akkoord.
