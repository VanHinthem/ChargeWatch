# Sprint 3 — publicatiebeleid voor statistieken (23 oktober 2026)

## Besluit en reikwijdte

Sprint 3 levert **een intern statistiekfundament**, geen gevalideerde kansvoorspeller. Totdat voldoende échte opeenvolgende NDW-waarnemingen zijn verzameld, worden EVSE/locatie-EWMA, 90-daagse uptime en duur-/herstelverdelingen **niet als gebruikersgerichte percentages of voorspellingen aangeboden**. Dit is een expliciete productgate, geen reden om het verzamelen van de gegevens te stoppen.

Dit document beschrijft de interpretatie van huidige tabellen 003–007; er is in deze wijziging **geen nieuwe API, databasekolom of UI** toegevoegd.

## Wel bruikbaar voor v1

- Actuele laadlocaties, EVSE-statussen en actuele tarieven uit de catalogus; altijd voorzien van bron-update-timestamp en duidelijke waarschuwing bij oude of onbekende status.
- Intern diagnostisch: `evse_poll_hour_coverage.covered_seconds` en `available_seconds` als **kandidaat-snapshotdekking**. Dit zijn seconden tussen aangetroffen, voldoende verse, consistent geclassificeerde snapshots, niet onafhankelijk bevestigde fysieke statusseconden.
- Intern diagnostisch: bronversheid (EVSE `last_updated` versus fetchmoment), bekende/onbekende status, coverage-aantallen, perioden zonder observatie en gebruikte statistiekmethode.

## Niet publiceren zonder aanvullende validatie

1. **Historische kans vrij** per EVSE of locatie: vereist voldoende representatieve, tijdgespreide observaties per *Nederlandse weekdag/uurslot* (168 slots), geen momentopname of synthetische replay. Locatiekans moet gezamenlijk uit alle EVSE's per locatie worden vastgesteld.
2. **Uptime over 90 dagen**: alleen uit voldoende gedekte, operationele of defecte intervallen; onbekend telt niet als storing of als operationeel. Label nooit '90-daagse uptime' indien slechts enkele uren zijn waargenomen.
3. **Duurhistogram, vrijkomen binnen 15/30/60 min**: overgangsinterval kent geen exact omslagmoment; bestaande censored tellers zijn geen gevalideerde duurdistributie.
4. **Percentage vrije EVSE's afgeleid uit test-/benchmarkdatabase**: deze bevat synthetische meetintervallen, herimports en handmatig teruggezette test-checkpoints en is niet representatief voor landelijke historische beschikbaarheid.

## Kwaliteitsvoorwaarden voor toekomstige publicatie

Een latere API of Android-widget mag een historische statistiek alleen uitgeven wanneer **alle** voorwaarden gelden:

- Het statistiekpad is expliciet gekozen en consistent (niet source-timestamp-EWMA mengen met snapshotkandidaatdekking).
- Er is een transparante kwaliteits-/dekkingsindicator per betrokken locatie/EVSE en tijdvak.
- Een echte reeks onafhankelijke NDW-downloads op normale scheduler-cadans is geobserveerd; synthetische/testdata telt niet mee voor publicatie.
- Fetch-gaps, statuswisselingen, verdwenen EVSE's, regressies en verouderde broninformatie zijn veilig uitgesloten.
- Voor locatie-statistieken zijn gezamenlijke EVSE-waarnemingen bruikbaar; UNKNOWN zonder bevestigde vrije EVSE is onbepaald.
- Minima voor tijdvenster, aantal waarnemingen en dekking per slot zijn vastgesteld met gemeten productieachtige datakwaliteit. **Er zijn bewust nog geen numerieke publicatiedrempels gekozen**: die zouden zonder kalibratie willekeurig zijn.
- Te weinig bewijs geeft geen percentage maar 'Onvoldoende historische gegevens'.

## Technische open punten vóór Sprint 3-merge

- `evse_poll_hour_coverage` gebruikt een maximale uurwaarde van 3600 seconden; de conflict-update laat momenteel een overschrijdend nieuw interval stilzwijgend liggen. Zorg dat inconsistente overlap traceerbaar is of bewust faalt, niet onopgemerkt de statistiek wijzigt.
- Herhalingen met een gewijzigde gziphash maar zonder EVSE-bronwijzigingen leveren sinds laatste fix geen kandidaatdekking; dit is alleen een feedbreed signaal en geen onafhankelijke bevestiging per EVSE.
- `docs/SPRINT3_STATISTICS.md` en eerdere secties van `docs/STATUS.md` bevatten gedateerde beschrijvingen. De actuele besluiten staan in dit document en bovenaan de status.
- Finale merge-gate: succesvolle regressies op de laatste code, onafhankelijke statische code-review (incl. SQL-fasecontract), statusupdate in dezelfde PR, daarna **expliciet gebruikersakkoord**. Geen deployment als onderdeel van Sprint 3.

## Feitenbasis

23 oktober 2026: korte echte NDW-vergelijking (203.328 EVSE's) toonde 199.753 volledig ongewijzigde records en slechts 904 timestamp-only wijzigingen. Nationale synthetische dubbelimport duurde 31,8 en 29,4 seconden voor 80.825 locaties en 203.328 EVSE's; dit bewijst schaalbaarheid onder de testomstandigheden, niet voorspelkwaliteit. De geïsoleerde database gaf cumulatief 62.386.732 kandidaatdekkingseconden in één UTC-uur en 64.486 EVSE's met >24 uur oude bron-timestamp. Na correctie slaagden tien lokale poll-integratiechecks. Deze feiten geven aanleiding tot het bovenstaande conservatieve beleid.
