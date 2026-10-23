# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026  
**Status:** Sprint 0–2 gemerged, geen deployment. Sprint 3 gestart als ontwerp/codevoorbereiding op featurebranch; schema-uitbreiding expliciet goedgekeurd; migratie toegevoegd maar niet uitgevoerd.

## Sprint 3 — actief en afgebakend

- Issue [#11](https://github.com/VanHinthem/ChargeWatch/issues/11); branch `feature/sprint3-observation-foundation`.
- Status: conservatieve EVSE-statusclassificatie en transactionele checkpoint-capture en conservatieve uptime-dagaccumulatie aan bulkimport toegevoegd, voorgestelde tabellen/observatiekwaliteit/transactieregels in [SPRINT3_STATISTICS.md](SPRINT3_STATISTICS.md).
- **Nog niet gerealiseerd:** uitvoering/validatie van de nu aanwezige migratie; PostgreSQL-validatie van lokale-dag-intervalstatistiek, 168-slot EWMA, duurprofielen en PostgreSQL-tests. Uptime-dagtotalen splitsen nu korte, ongewijzigde-statusintervallen over Europe/Amsterdam-kalenderdagen, inclusief DST; niet getest en bronmomentdekking kan onvoldoende zijn. Checkpoints slaan inmiddels uitsluitend verse source-observaties op en blijven onberekend voor duren.
- **Besluit:** gebruiker gaf op 22 oktober expliciet akkoord op een additieve, versioneerbare schema-uitbreiding. `db/migrations/003_statistics.sql` toegevoegd met zeven tabellen, index en rolrechten; bulkimport roept nu `ObservationCheckpoints.Capture` aan binnen dezelfde transactie en vereist daarom migratie 003; import op ongemigreerde testdatabases faalt expliciet. Niets toegepast op gebruikersdatabases.
- Checks: gebruiker bevestigde op 23 oktober 2026 succesvolle initialisatie van aparte chargewatch_statistics_test database, rol-GRANTs, 003_statistics migratie (INSERT 0 1) en db/tests/003_statistics_smoke.sql (BEGIN, beide DO succesvol, ROLLBACK). Dit valideert schema/rolrechten/DST-smoketest, nog niet .NET-importintegratie. GitHub-inhoud gecontroleerd. Lokale .NET-build niet uitgevoerd: .NET SDK ontbreekt in agentomgeving. Geen CI-workflow aanwezig. 90-daagse Nederlandse kalenderdagretentie toegevoegd; SQL-smoketest uitgebreid met daadwerkelijke daggrenssplitsing, wintertijdcontrole en rechtencontrole. Gemiste SELECT-grant op schema_migration gecorrigeerd in de migratie. SQL-smoketest toegevoegd in `db/tests/003_statistics_smoke.sql` (schema, DST-daglengte en rollback-fixture), nog niet uitgevoerd omdat de agentomgeving geen dotnet, psql of docker bevat.
- Volgende stap: checkpoint-implementatie lokaal builden en op geïsoleerde gemigreerde testdatabase valideren; daarna tijdgewogen statistiekberekeningen toevoegen en gerichte tests draaien op een geïsoleerde database. Geen merge/deployment zonder apart akkoord.

---

# Historische overdracht — Sprint 0–2

**Bijgewerkt:** 22 oktober 2026  
**Status:** Sprint 0, 1 en 2 gemerged; geen deployment. Sprint 2 is op 22 oktober 2026 via squashmerge `6ed5800895dee5facad925e4c5d045f612fd62ae` afgerond.

## Afgeronde sprints

- Sprint 0: [PR #3](https://github.com/VanHinthem/ChargeWatch/pull/3), issue #2 gesloten. NDW OCPI-broncontract onderzocht; beide gzip-feeds met HTTP 304 bevestigd. Zie [brononderzoek](NDW_DATA_DISCOVERY.md), [snapshot](NDW_SNAPSHOT_2026-10-22.md) en [vergelijking](NDW_COMPARISON_2026-10-22.md).
- Sprint 1: [PR #6](https://github.com/VanHinthem/ChargeWatch/pull/6), issue #5 gesloten; squashmerge `1c470982620c533ba3ca687df918fcda0345d469`. .NET 10 NDW-importer, PostgreSQL 18/PostGIS-schema, gescheiden import- en leesrol, transactionele COPY/staging-bulkimport, bescherming tegen ongewijzigde updates. Geen productie- of NAS-deployment.
- Workflow/status-overdracht is vastgelegd via PR #7; mergeklare status hoort voortaan in dezelfde feature-PR.

## Afgerond werk — Sprint 2

- Issue: [#8](https://github.com/VanHinthem/ChargeWatch/issues/8) — automatische NDW-verversing en veilige scheduler.
- Branch: `feature/sprint2-ndw-refresh`.
- PR: [#9](https://github.com/VanHinthem/ChargeWatch/pull/9) — **gemerged op 22 oktober 2026**.
- Geen GitHub Actions-workflow aanwezig; er zijn geen CI-runs/statuschecks voor deze PR.

### Geïmplementeerd

- Nieuwe importmodi `--watch` en `--watch-once`.
- NDW Locations standaard elke 5 minuten; Tariffs elke 60 minuten.
- Conditionele GET met bewaarde `ETag` en `Last-Modified`.
- Vaste lokale cache plus `refresh-state.json`.
- Nieuwe downloads blijven pending totdat de bestaande transactionele bulkimport succesvol commit; pas daarna worden ze de vaste cache.
- Bij 304/304 wordt de database-import overgeslagen.
- Bij wijziging van één feed wordt de laatst geldige cache van de andere feed gebruikt.
- HTTP-/gzip-/JSON-/importfouten behouden de vorige broncache en gepubliceerde database.
- Minimumaantal locaties standaard 50.000 als veiligheidsgrens.
- Stale-waarschuwing volgt specifiek de laatste succesvolle Locations-controle; een tariefcheck maskeert geen falende statusfeed.
- Continuous mode blijft na een mislukte cyclus draaien en ondersteunt gecontroleerd stoppen met Ctrl+C.
- Configuratie en validatie staan in [SPRINT2_REFRESH.md](SPRINT2_REFRESH.md).
- README is bijgewerkt naar de actuele backendstatus.

### Validatie en resterende review

De agent heeft de GitHub-diff en mergeability gecontroleerd. **Gebruiker bevestigde op 22 oktober 2026 een geslaagde lokale .NET 10-build**: `dotnet build .\\src\\ChargeWatch.Importer\\ChargeWatch.Importer.csproj` — restore 6,0 s, compilatie 2,7 s, totale build 9,3 s. De agentcontainer kon zelf geen checkout uitvoeren. **Eerste `--watch-once` bevestigd geslaagd op 22 oktober 2026:** Locations HTTP 200 (18.382.191 bytes), Tariffs HTTP 200 (4.143.448 bytes), 80.785 locaties, 203.130 EVSE's, 203.823 connectoren, 101.209 tarieven; transactionele bulkcommit in 26,218 s. Tweede `--watch-once` bevestigd: Locations HTTP 200 (18.382.637 bytes), Tariffs HTTP 304; bestaande Tariffs-cache hergebruikt, bulkcommit succesvol in 21,858 s, dezelfde catalogusaantallen. Dit bewijst het scenario met slechts één gewijzigde feed. Derde `--watch-once` bevestigd: Locations HTTP 200 (18.384.062 bytes), Tariffs HTTP 304, bulkcommit in 22,513 s, aantallen ongewijzigd. NDW Locations veranderde tijdens drie opeenvolgende runs; niet verder live herhalen voor 304/304. Gecontroleerde 304/304-test op 22 oktober geslaagd met lokale HTTP-server op 127.0.0.1:8765: beide feeds HTTP 304 en `NDW source unchanged (304); database import skipped.` De foutpadtest is ook geslaagd: Locations-URL op ongebruikte lokale poort 8766 veroorzaakte een geweigerde verbinding, melding `Refresh failed; previous cache/database retained` en exitcode `1`. Er startte geen bulkimport. Let op: database-run-aantallen en cachebestanden zijn nog niet afzonderlijk gecontroleerd. Gebruiker bevestigde dat continuous `--watch` actief blijft en op 22 oktober 2026 correct afsluit met Ctrl+C.

Build en eerste landelijke import zijn geslaagd. Voer nu de tweede `--watch-once` uit vanaf de featurebranch:

```powershell
git switch feature/sprint2-ndw-refresh
git pull
dotnet build .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj

$env:CW_IMPORT_CONNECTION = "Host=localhost;Port=5432;Database=chargewatch_benchmark;Username=chargewatch_import;Password=<password>"
$env:CW_NDW_CACHE_DIR = "$PWD\.local\ndw-cache"
dotnet run --project .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj -- --watch-once
```

De gecontroleerde 304/304-test en het HTTP-foutpad zijn bevestigd. Continuous `--watch` blijft actief en sluit gecontroleerd af met Ctrl+C, beide bevestigd. Optioneel blijft een afzonderlijke controle van `import_run`-aantallen en cachebestand-hashes mogelijk; dit is niet uitgevoerd. Gebruik een aparte test/benchmarkdatabase; bestaande lokale databases/volumes niet verwijderen.

## Vastgestelde NDW-data

Snapshot van 22 oktober 2026: **80.777 locaties, 203.114 EVSE's, 203.807 connectoren en 101.209 tarieven**. Locatie- en tariefkey = `country_code + party_id + id`; EVSE-key = locatie + `uid`, extern `evse_id` apart. Van 179.241 tariefverwijzingen werden 174.161 geresolveerd en 5.080 niet; 24.566 connectoren hebben geen tariefverwijzing. Onbekend tarief is niet €0.

## Sprint 1 — werkelijk uitgevoerde controles

Gebruiker bevestigde werkende .NET 10-build, PostgreSQL/PostGIS-initialisatie, Schietspoel-fixture, herhaalbaarheid, rollback bij onvolledige input en SELECT-only-rechten van `chargewatch_read`. De landelijke catalogusaantallen matchen de NDW-bron.

Schone benchmarkdatabase `chargewatch_benchmark`, originele ontwikkelcatalogus intact:
- Leeg vóór import: **16.430.783 bytes**.
- Eerste landelijke bulkimport: **28,100 s**, daarna **224.024.255 bytes**.
- Tweede identieke landelijke bulkimport: **27,167 s**, exitcode 0, daarna **224.147.135 bytes**.
- Extra databaseopslag bij herimport: **122.880 bytes** (~0,055%). Beide runs succesvol; aantallen identiek en 2 `completed`-runs.
- Oudere rij-voor-rij-import: **828,496 s** (andere uitgangssituatie).
- Zie [Sprint-1-instructies](SPRINT1_IMPORT.md).

## Richting na Sprint 2

V1 blijft: landelijke actuele laadstatus/tarieven, kaart/zoeken, alternatieven, lokale Android-favorieten en widget, compacte beschikbaarheidsstatistieken. .NET 10 importer → PostgreSQL 18/PostGIS → read-only GET API → Kotlin/Compose/Glance. Geen accounts, push/notificaties of betalingen in v1.

Na afgeronde Sprint 2 is de logische volgende feature het statistiekfundament vanaf opeenvolgende imports **of** de read-only locatie-API. Bepaal de volgorde in een nieuw issue; Sprint 2 is afgerond.

Open punten: herpublicatielicentie/attributie NDW, langdurige bronidentiteit en datakwaliteit, volledige wijzigingsscenario's/ontbrekende records, CI, statistiekopslag en toekomstige API. Geen productie-/NAS-deployment uitgevoerd.

## Overdracht / vaste werkwijze

Lees `AGENTS.md`, `docs/WORKFLOW.md`, deze status, ROADMAP, PRODUCT en ARCHITECTURE. Controleer GitHub-issues, PR's, branches en CI opnieuw. PR #9 is na expliciete goedkeuring gemerged en de main-SHA geverifieerd. Er is geen deployment uitgevoerd. De volgende agent moet eerst nieuwe scope afbakenen in een issue en featurebranch; voor toekomstige PR's blijven expliciete merge- en deploymentgoedkeuring verplicht.
