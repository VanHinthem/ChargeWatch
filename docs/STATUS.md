# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026  
**Status:** Sprint 0 en 1 gemerged. Sprint 2 geïmplementeerd in PR #9 en wacht op lokale inhoudelijke validatie; niet gemerged en niet gedeployed.

## Afgeronde sprints

- Sprint 0: [PR #3](https://github.com/VanHinthem/ChargeWatch/pull/3), issue #2 gesloten. NDW OCPI-broncontract onderzocht; beide gzip-feeds met HTTP 304 bevestigd. Zie [brononderzoek](NDW_DATA_DISCOVERY.md), [snapshot](NDW_SNAPSHOT_2026-10-22.md) en [vergelijking](NDW_COMPARISON_2026-10-22.md).
- Sprint 1: [PR #6](https://github.com/VanHinthem/ChargeWatch/pull/6), issue #5 gesloten; squashmerge `1c470982620c533ba3ca687df918fcda0345d469`. .NET 10 NDW-importer, PostgreSQL 18/PostGIS-schema, gescheiden import- en leesrol, transactionele COPY/staging-bulkimport, bescherming tegen ongewijzigde updates. Geen productie- of NAS-deployment.
- Workflow/status-overdracht is vastgelegd via PR #7; mergeklare status hoort voortaan in dezelfde feature-PR.

## Actief werk — Sprint 2

- Issue: [#8](https://github.com/VanHinthem/ChargeWatch/issues/8) — automatische NDW-verversing en veilige scheduler.
- Branch: `feature/sprint2-ndw-refresh`.
- PR: [#9](https://github.com/VanHinthem/ChargeWatch/pull/9) — **open, mergeable, niet gemerged**.
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

### Nog te valideren vóór merge

De agent heeft de GitHub-diff en mergeability gecontroleerd. Een lokale build kon in de agentcontainer niet worden uitgevoerd omdat die omgeving GitHub/DNS niet kon bereiken om de branch te klonen. Dit is **geen** buildresultaat.

Voer lokaal vanaf de featurebranch uit:

```powershell
git switch feature/sprint2-ndw-refresh
git pull
dotnet build .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj

$env:CW_IMPORT_CONNECTION = "Host=localhost;Port=5432;Database=chargewatch_benchmark;Username=chargewatch_import;Password=<password>"
$env:CW_NDW_CACHE_DIR = "$PWD\.local\ndw-cache"
dotnet run --project .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj -- --watch-once
```

Daarna dezelfde `--watch-once` direct opnieuw uitvoeren. Verwacht bij ongewijzigde NDW-data 304 voor beide feeds, `database import skipped` en geen extra `import_run`. Test vervolgens één foutpad en kort `--watch`/Ctrl+C. Gebruik een aparte test/benchmarkdatabase; bestaande lokale databases/volumes niet verwijderen.

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

Na succesvolle Sprint 2-validatie en expliciet merge-akkoord is de logische volgende feature het statistiekfundament vanaf opeenvolgende imports **of** de read-only locatie-API. Kies dat pas na merge; trek het niet in PR #9.

Open punten: herpublicatielicentie/attributie NDW, langdurige bronidentiteit en datakwaliteit, volledige wijzigingsscenario's/ontbrekende records, CI, statistiekopslag en toekomstige API. Geen productie-/NAS-deployment uitgevoerd.

## Overdracht / vaste werkwijze

Lees `AGENTS.md`, `docs/WORKFLOW.md`, deze status, ROADMAP, PRODUCT en ARCHITECTURE. Controleer GitHub-issues, PR's, branches en CI opnieuw. PR #9 vereist eerst lokale inhoudelijke validatie door Ramon. **Niet mergen of deployen zonder expliciet akkoord.** Na akkoord actuele head/mergeability/checks opnieuw controleren, daarna pas merge; vervolgens `docs/STATUS.md` op main verifiëren.
