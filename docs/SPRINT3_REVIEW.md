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


## Landelijke read-only audit — uitgevoerd 23 oktober 2026

Bron: gebruiker voerde `db/tests/sprint3_ndw_source_quality_readonly.sql` uit op `chargewatch_benchmark`; transactie BEGIN READ ONLY ... COMMIT, zonder mutaties. Snapshot bevat **80.787 locaties, 203.138 EVSE's**. Nul ontbrekende EVSE-brontimestamps. **15.354 onbekende statusklassen (7,56%)**, waaronder UNKNOWN 10.134, REMOVED 5.198, PLANNED 22. Beschikbare EVSE's 136.485; bezet 47.740; storing 3.559. Aantoonbaar vrije locaties 69.950, nul-vrij 7.864, nul-vrij onbepaald 2.973.

Slechts **19.139/80.787 locaties (23,69%)** hebben één gelijke EVSE-brontimestamp; slechts **16.653 (20,61%)** combineren dat met uitsluitend bekende statussen. Bij de 63.081 locaties met twee EVSE's: slechts 9.827 gelijklopende timestamps (15,58%); 8.623 zijn bovendien zonder onbekende status. Per groep timestamp gelijk: 1 EVSE 7.733/7.733; 2 EVSE's 9.827/63.081; 3–4 699/5.966; 5–10 578/2.891; 11+ 302/1.116.

EVSE timestampleeftijd t.o.v. de audit: 0 binnen 1 uur, 52.946 binnen 24 uur (26,06%), 177.810 binnen 7 dagen, mediane leeftijd 27,90 uur, P90 244,76 uur, P99 8.318,28 uur. Laatste geslaagde benchmarkimport: **2026-10-22 14:31:54 UTC**, 5 completed imports. **Let op:** leeftijd t.o.v. audit is niet leeftijd t.o.v. het importmoment; dataset is gisteren geïmporteerd, dus dit is geen meting van NDW-feed-latency of pollingfrequentie.

**Besluit:** exact gelijke EVSE-brontimestamps is geen bruikbare algemene toelatingsregel voor locatieprofiledekking. Herzie observatiestrategie voordat statistieken als beschikbaarheidskans worden gepubliceerd. Eén snapshot bewijst niet dat `last_updated` uitsluitend op statusverandering wijzigt; dit moet met ten minste twee opeenvolgende bronversies worden gecontroleerd. Sprint 3 blijft draft, geen merge of deployment.
