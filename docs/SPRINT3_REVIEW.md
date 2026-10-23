# Sprint 3 — code- en datakwaliteitsreview (23 oktober 2026)

## Gevalideerd op de geïsoleerde testdatabase
- Schietspoel-identieke replay: geen fictieve uurdekking.
- EVSE-uursegmentatie over UTC-uurgrens; positieve 3600s/negatieve 300s finalisatie; EWMA slot 35.
- Locatie-uurprofiel voor twee synchroon bijgewerkte AVAILABLE-EVSE's; UNKNOWN sluit coverage conservatief uit.
- EVSE-overgang en ononderbroken reeks/gat als gecensureerd, zonder gefingeerde duurbuckets.
- Locatie 0-vrij -> vrij en gat: 1 herstel, 2 censored; identieke laatste replay verandert niets.
- Alle onderdelen draaien transactioneel in geïsoleerde `chargewatch_statistics_test`. Geen live uitrol en geen landelijke replay/polling gevalideerd.

## Harde kwaliteitsbeperkingen
1. NDW `last_updated` beschrijft een bronwijziging en bewijst **niet** dat de status elke vijf minuten opnieuw bevestigd is. De huidige intervalregel accepteert alleen strictly nieuwere timestamps bij onveranderde status; echte feeds kunnen hierdoor vrijwel nul dekking geven. Geen betrouwbare landelijke uptime/voorspelling zonder echte data-check.
2. Voor locatie-uurdekking moeten **alle EVSE's exact gelijklopende bronmomenten** hebben; bij asynchrone NDW-updates vervalt dekking, ook als de locatiestatus evident 'vrij' is.
3. Statuswisselingen hebben een onbekend omslagmoment. De duurhistogramarrays blijven op nul. Ze mogen niet als 'geen gebeurtenissen' of echte duurverdeling in UI/API geïnterpreteerd worden.
4. Er is **geen volledige eventgeschiedenis**: verdwenen EVSE's, late broncorrecties, volgorde van EVSE-wijzigingen en ontbrekende status moeten expliciet veilig blijven; de bestaande synthetische tests dekken deze gevallen nog niet allemaal.
5. De urenfinalizer sluit een verstreken uur na een latere bronwaarneming af, ook bij lage dekking. Late backfill mag dit uur nooit ten onrechte opnieuw laten meetellen; het beleid is bewust conservatief.
6. De benchmark betreft nog niet het volledige Sprint 3-statistiekpad op de landelijke 80k/200k+ dataset. Geen uitspraak over nationale runtime/opslaggroei.

## Reviewbevindingen in huidige code
- `ObservationCheckpoints.Capture` splitst een grote SQL-string bij een letterlijke commentaarmarker. Functioneel bewezen in tests, maar fragiel bij verplaatsen van SQL-commentaar. Voor merge: expliciet fasecontract maken of de marker als constante met assertie beter isoleren.
- `location_zero_available_summary.recovered_periods` is een **getelde waargenomen statusovergang**, niet een precies getimede herstelduur. `censored_periods` is niet optelbaar als volledige perioden of een histogram.
- Er zijn historische statusregels bovenaan `docs/SPRINT3_STATISTICS.md` en `docs/STATUS.md` die nog 'niet geïmplementeerd/niet getest' zeggen. Deze moeten worden gelezen als gedateerde voortgangsnotities; de actuele status is hieronder leidend.

## Eerstvolgende gate vóór merge
Een *read-only* datakwaliteitscheck op werkelijke NDW-gegevens: verdeling/versheid van EVSE-`last_updated`, aandeel EVSE's met gelijke timestamp op een locatie, aandeel onbekende status, aandeel locaties die voldoen aan het synchronisatiecriterium. Indien onvoldoende dekking: statistieken niet publiceren als voorspellende kans of uptime. Daarna gerichte regressie op ontbrekende EVSE/statuswissel en performance check met landelijke benchmark **zonder de bestaande benchmarkdatabase te muteren**.

**Besluit:** Sprint 3 blijft draft. Geen merge/deployment voordat bronkwaliteit en resterende relevante edgecases zijn beoordeeld; zonder voldoende dekking nadrukkelijk alleen een statistiekfundament, geen productiegeschikte beschikbaarheidsvoorspelling.
