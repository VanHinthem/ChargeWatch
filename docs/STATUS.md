# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026  
**Status:** Sprint 0 gemerged; Sprint 1 in uitvoering op `feature/sprint1-import-foundation`. Geen merge/deployment van Sprint 1.

## GitHub
- Sprint 0: [PR #3](https://github.com/VanHinthem/ChargeWatch/pull/3) gemerged; [issue #2](https://github.com/VanHinthem/ChargeWatch/issues/2) gesloten.
- Statusoverdracht: [PR #4](https://github.com/VanHinthem/ChargeWatch/pull/4) gemerged op `main`.
- Sprint 1: [issue #5](https://github.com/VanHinthem/ChargeWatch/issues/5), actieve branch `feature/sprint1-import-foundation`.
- Open PR en CI-status opnieuw controleren bij volgende start.

## Sprint 0 vastgesteld
- Landelijke NDW-data: 80.777 locaties, 203.114 EVSE's, 203.807 connectoren, 101.209 tarieven.
- Locatiekey `country_code + party_id + id`, EVSE-key locatie + `uid`; bron-`evse_id` apart.
- 24.566 connectoren zonder tariefverwijzingen; van 179.241 verwijzingen 174.161 geslaagd, 5.080 niet. Onbekende tarieven nooit als nul presenteren.
- Voor beide landelijke gzip-feeds werkt HTTP 304 bij conditionele GET.
- Zie [snapshot](NDW_SNAPSHOT_2026-10-22.md) en [vergelijking](NDW_COMPARISON_2026-10-22.md).

## Sprint 1 — uitgevoerd op featurebranch
- `docker-compose.yml`: lokale PostgreSQL 18/PostGIS-omgeving; wachtwoorden via niet-gecommit .env.
- `db/init/001_schema.sql`: startmodel Location, Evse, Connector, Tariff, ConnectorTariff, ImportRun.
- `db/init/002_roles.sh`: aparte import-schrijver en alleen-lezen rol.
- `src/ChargeWatch.Importer`: .NET 10 CLI; gzip JSON streaming, transactionele catalogus-upserts, bronkey-duplicaatcontrole, minLocations guard, geen onveilige massadeletes.
- `tools/build_sprint1_fixture.py`: maakt uit de echte ingekorte Schietspoel-fixture kleine gzip-bestanden.
- [Uitvoerings- en validatieinstructies](SPRINT1_IMPORT.md).

## Eerste lokale Docker-validatie (22 oktober 2026)
- Windows Docker Desktop: image `postgis/postgis:18-3.6` opgehaald; PostgreSQL 18.6 initdb en alle SQL-schemaobjecten succesvol aangemaakt.
- Container stopt daarna met exitcode 127 bij `002_roles.sh: cannot execute: required file not found`. Waarschijnlijke CRLF/shebang-incompatibiliteit van Windows-checkout; nog niet definitief bevestigd.
- `.gitattributes` toegevoegd (`*.sh` en `*.sql` LF) en Windows-diagnose/normalisatie vastgelegd in `docs/SPRINT1_IMPORT.md`.
- Datavolume is na gedeeltelijke initialisatie mogelijk onvolledig; uitsluitend een aantoonbaar lege ChargeWatch-ontwikkelvolume opnieuw initialiseren na bewuste bevestiging, nooit NAS/production data verwijderen.

## Eerste succesvolle lokale fixture-import (22 oktober 2026)
- Gebruiker bevestigde .NET SDK 10.0.402; aanvankelijke restore blokkeerde op een globale, niet-geautoriseerde Azure DevOps-feed (NU1301 401).
- Projectgebonden `NuGet.config` met alleen nuget.org toegevoegd; `dotnet restore ... --configfile NuGet.config` slaagde.
- `dotnet run --no-restore --project src/ChargeWatch.Importer -- data/sprint1-fixture/locations.json.gz data/sprint1-fixture/tariffs.json.gz 1` gaf `Committed: 1 locations, 2 EVSEs, 2 connectors, 1 tariffs.`.
- Dit bevestigt compiler/importtransactie met echte fixture; idempotentie **bevestigd**: tweede identieke import succesvol, daarna 1 locatie, 2 EVSE's, 2 connectoren, 1 tarief, 2 tariefkoppelingen, 2 import_runs. Rollback bij minLocations=2 met één locatie geslaagd (`No changes published`); import_run blijft 2. Databasepermissies door gebruiker bevestigd: `chargewatch_import` SELECT/INSERT/UPDATE/DELETE; `chargewatch_read` SELECT en geen INSERT/UPDATE/DELETE op `charging_location`.

## Nog niet uitgevoerd / bewezen
- Agentomgeving heeft geen .NET SDK of Docker; de **gebruiker heeft lokaal** PostgreSQL-init, .NET-restore/build en eerste fixture-import succesvol uitgevoerd.
- Ook nog geen nationale PostgreSQL opslag-/importbenchmark en geen gerealiseerde statistische accumulators.
- Huidige importer gebruikt rij-voor-rij SQL; national-scale verwerking en schaalbaarheid zijn onbewezen.
- Nog geen ETag-cache in de CLI, geen productie-ready staging/publish en geen verwijderingsreconciliatie.
- Technische keuze voor definitieve opslagvorm pas na meting; NDW-attributie/licentie voor distributie nog onderzoeken.

## Eerstvolgende actie
1. Lokaal `docker compose up -d db` en `dotnet run` volgens [Sprint 1 instructies](SPRINT1_IMPORT.md).
2. Fixture-import en herhaling + onvolledige snapshot rollback + read-only permissies toetsen.
3. Defecten op **dezelfde branch** oplossen; daarna beperkte landelijke proefimport en geheugen/opslag/doorlooptijd meten.
4. Werk PR en deze status bij; vraag inhoudelijke validatie en **expliciete mergegoedkeuring**.
5. Geen NAS-/productiedeployment zonder apart akkoord.

## Landelijke proefimport — geslaagd (22 oktober 2026)

De gebruiker heeft de oorspronkelijke landelijke NDW-gzip via de .NET 10 importer geïmporteerd met minimum 70.000 locaties. Gemeten duur: **13 min 48,496 s (828,496 s)**. Na afloop gecontroleerde databaseaantallen: **80.777 locaties, 203.114 EVSE's, 203.807 connectoren, 101.209 tarieven, 179.241 tariefkoppelingen, 3 geslaagde import_runs** (2 fixtures + 1 landelijke). PostgreSQL meldde **214 MB** databaseomvang. Hiermee is daadwerkelijke landelijke import/commit bevestigd; deze doorlooptijd is een eerste nulmeting, geen prestatiegarantie. De importer gebruikt nog per-record SQL; bulk/staging is logische optimalisatierichting. De omvang zegt nog niets over toekomstige statistiekopslag of WAL/backupruimte.

## Bulkoptimalisatie in uitvoering

- De nationale baseline van 828,496 seconden en 214 MB blijft de vergelijking.
- `BulkImporter.cs` toegevoegd, opt-in via `--bulk`: gzip naar tijdelijke jsonb-stage via PostgreSQL COPY, daarna set-based upserts en tariefkoppelingen in één transactie.
- Bestaande rij-voor-rij CLI blijft beschikbaar; database en rollen zijn niet gewijzigd.
- **Lokale build en Schietspoel-bulkfixture geslaagd** (gebruikersrun 22 oktober 2026): `Staged: 1 locations, 2 EVSEs, 2 connectors, 1 tariffs`, `Bulk committed` in 0,6500489 seconden. Landelijke **elapsed time** bulkherimport gemeten: 37,25033 seconden via `Measure-Command`, versus 828,49582 seconden voor oorspronkelijke eerste import (~22,2x). Vervolgens afzonderlijk **commit bevestigd**: nieuwste import_run `ndw-ocpi-bulk/completed` met 80.777 locaties, 203.114 EVSE's, 203.807 connectoren. Catalogustellingen bleven exact 80.777 locaties, 203.114 EVSE's, 203.807 connectoren, 101.209 tarieven en 179.241 tariefkoppelingen. Databaseomvang na herimport **402 MB** (voorheen 214 MB, +188 MB); mogelijke PostgreSQL MVCC/indexgroei onderzoeken vóór conclusies. Bulkrollback nog niet bewezen. De vergelijking is eerste import versus herimport, dus geen gelijke cold-startbenchmark.
- Geen nieuwe PR/merge/deployment; alle wijzigingen in draft PR #6.

## Productrichting
.NET 10 importer → PostgreSQL 18/PostGIS → GET-only read-only API → Android Compose + Glance; 168 weekdag/uur-EWMA-profielen per EVSE én locatie en 90-daagse uptime later. Geen accounts, notificaties of betalingen in v1.

## Database-opslag na landelijke bulkherimport

Gebruiker controleerde `pg_stat_user_tables`: tariff 163 MB, evse 71 MB, connector 60 MB, charging_location 47 MB, connector_tariff 45 MB, spatial_ref_sys circa 7 MB; alle `n_dead_tup = 0` (statistische schatting, niet bewijs van afwezigheid van bloat). 5 geslaagde importruns. Totale databaseomvang eerder na herimport 402 MB. Nog uit te zoeken of herhaalde ongewijzigde upserts onnodige WAL/rijversies en indexgroei veroorzaken.

## Bulkrollback bevestigd (22 oktober 2026)

Gebruiker voerde `--bulk` met één-locatie-Schietspoel-fixture en `minLocations=2` uit. Resultaat: `Bulk rollback: Only 1 locations; expected >= 2`. `SELECT COUNT(*) FROM import_run` bleef **5**. De transactionele afwijzing van een te kleine snapshot is daarmee lokaal bevestigd. Nog open: ongewijzigde records overslaan bij upserts om vermijdbare writes te beperken; groei 214→402 MB is indicatie, geen oorzakelijk bewijs.

## Ongewijzigde-upsertoptimalisatie (22 oktober 2026)

Bulkmodus op dezelfde branch aangepast: Location, EVSE, Connector en Tariff werken uitsluitend bij wanneer de kolomtuple `IS DISTINCT FROM` is. ConnectorTariff wordt set-wise vergeleken met de gewenste koppelingen, zodat identieke links niet meer verwijderd/ingevoegd worden. **Nog niet gebouwd of lokaal gevalideerd.** Eerst fixture en vervolgens identieke landelijke herimport controleren op aantallen, tijd en opslag. Geen schema-update of deployment.

## Validatie gewijzigde-rijoptimalisatie (22 oktober 2026)

Gebruiker heeft nieuwe `--bulk` met de Schietspoel-fixture succesvol uitgevoerd: `Bulk committed` in 0,5878821 seconde. Landelijke herimport via `Measure-Command` duurde 22,9978642 seconden tegenover 37,25033 seconden voor vorige bulkversie en 828,49582 seconden voor rij-voor-rij. **Landelijke commitstatus, aantallen en nieuwe databaseomvang nog niet geverifieerd na deze run**; `Measure-Command` onderdrukt de procesuitvoer. Geen buildlog afzonderlijk aangeleverd, fixture-run toont wel dat de actuele CLI startte.

## Afsluitende bulkvalidatie (22 oktober 2026)

Gebruiker bevestigde laatste `--bulk` herimport: 22,9978642 seconden volgens `Measure-Command`; actuele `import_run` is `ndw-ocpi-bulk / completed`. Database bevat nog steeds 80.777 locaties, 203.114 EVSE's, 203.807 connectoren, 101.209 tarieven en 179.241 tariefkoppelingen. `pg_size_pretty(pg_database_size('chargewatch'))` bleef **402 MB** voor/na deze laatste herimport (afgerond, geen exacte bytevergelijking). Ongewijzigde-record-upserts en diff van tariefkoppelingen werken daarmee op de onderzochte snapshot.

**Sprint 1 gereed voor gebruikersreview, niet gemerged.** Bekende beperkingen: geen werkende scheduler/ETag in importer, geen productierijpe staging voor updategaten/deletes, geen statistics-pipeline, geen CI; test van veranderde records en tariefkoppelingen beperkt. NDW-licentie/attributie nog controleren vóór publieke distributie. Performance betreft herimport op warme bestaande dataset, geen cold-startvergelijking.

## Schone databasebenchmark — 22 oktober 2026

Afzonderlijke `chargewatch_benchmark`-database aangemaakt met hetzelfde schema en rechten, originele ontwikkelcatalogus bleef intact. Grootte vóór import **16.430.783 bytes**. Eerste nationale `--bulk` in **28,1003812 s** (`Measure-Command` zonder zichtbare exitcode), daarna **224.024.255 bytes**. Identieke tweede import: console `Staged: 80777 locations, 203114 EVSEs, 203807 connectors, 101209 tariffs` en `Bulk committed ... in 00:00:25.0663539`; PowerShell stopwatch **27,167278 s**, exitcode **0**. Database daarna **224.147.135 bytes**, toename **122.880 bytes (~0,055%)**. Eerste versus tweede import dus vergelijkbare snelheid; ongewijzigde data veroorzaakt bij deze run nauwelijks extra opslag. Exacte catalogustellingen/geslaagde runs in benchmarkdatabase desgewenst nog zelfstandig controleren. Geen merge of deployment.
