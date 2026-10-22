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
- Dit bevestigt compiler/importtransactie met echte fixture; idempotentie **bevestigd**: tweede identieke import succesvol, daarna 1 locatie, 2 EVSE's, 2 connectoren, 1 tarief, 2 tariefkoppelingen, 2 import_runs. Rollback en alleen-lezen rol nog niet gecontroleerd.

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

## Productrichting
.NET 10 importer → PostgreSQL 18/PostGIS → GET-only read-only API → Android Compose + Glance; 168 weekdag/uur-EWMA-profielen per EVSE én locatie en 90-daagse uptime later. Geen accounts, notificaties of betalingen in v1.
