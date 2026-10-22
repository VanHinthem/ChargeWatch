# Sprint 1 — eerste databaselaag en NDW-importfundament

**Issue:** #5; **branch:** `feature/sprint1-import-foundation`  
**Status:** eerste implementatie, **niet** tegen PostgreSQL of .NET 10 gevalideerd in deze agentrun.

## Implementatie

- PostgreSQL 18/PostGIS Compose-config voor lokale development. Alleen localhost-poort.
- Initieel schema voor Location, Evse, Connector, Tariff, ConnectorTariff en ImportRun.
- Samengestelde bronidentiteit: location `country_code + party_id + id`, evse `location + uid`, tariff `country_code + party_id + id`.
- `chargewatch_import` schrijfrechten en `chargewatch_read` alleen SELECT. De eigenaar is uitsluitend voor schema-setup; nooit gebruiken in de publieke API.
- .NET 10 consoleimporter met Npgsql, streamt gzip JSON-arrays record-voor-record.
- Upsert zonder massadelete; `tariff_ids` zonder corresponderend tarief mogen blijven bestaan. Geen nulprijzen.
- Eén database-transactie over locations en optionele tariffs: bij ongeldige ID of duplicaat rollback.
- `minLocations` beschermt tegen kleine/onvolledige feed; **moet bij landelijke run bewust op relevante landelijke ondergrens worden gezet**.
- Behoudt bestaande entities die in nieuwe snapshot ontbreken: nog **geen** volledige reconciliation, freshness-publicatie of statusstatistiek.
- Eenvoudige per-record SQL: geschikt voor fixture/proefimport, **landelijke prestatie nog niet gemeten**. Voor productie waarschijnlijk bulk/staging nodig.

## Lokale controle (Windows, Docker Desktop en .NET 10 SDK)

1. Kopieer `.env.example` naar `.env` en vervang de drie wachtwoorden. `.env` is Git-ignored.
2. Start `docker compose up -d db` en bekijk `docker compose logs db`.
3. Maak vanuit de brongebaseerde Schietspoel-fixture kleine echte OCPI-arrays:
   ```powershell
   python tools/build_sprint1_fixture.py
   ```
4. Zet `CW_IMPORT_CONNECTION` naar de importer-rol, bijvoorbeeld:
   ```powershell
   $env:CW_IMPORT_CONNECTION = "Host=127.0.0.1;Port=5432;Database=chargewatch;Username=chargewatch_import;Password=<eigen importwachtwoord>"
   dotnet run --project src/ChargeWatch.Importer -- data/sprint1-fixture/locations.json.gz data/sprint1-fixture/tariffs.json.gz 1
   ```
5. Voer dezelfde import opnieuw uit (idempotentie: aantallen catalogusrijen blijven gelijk). Controleer via `psql` of een DB-client: locaties 1, EVSE's 2, connectors 2, tariffs 1 en tariefkoppelingen 2. `import_run` telt geslaagde pogingen.
6. Controleer rolgrens: `chargewatch_read` kan tabellen lezen maar geen INSERT uitvoeren.
7. Foutpad: met `minLocations=2` en de één-locatie-fixture moet de gehele transactie terugrollen. Bij vooraf bestaande data verandert de gepubliceerde catalogus niet.
8. Pas na bovenstaande: realistische nationale import uitvoeren en duur/databasegrootte meten. De huidige rij-voor-rij importer kan daarvoor te traag zijn; optimaliseer pas op gemeten bottlenecks.

## Beperkingen en open risico's

- Er is geen build/test/Compose-run uitgevoerd in deze agentomgeving (geen dotnet of docker aanwezig).
- SQL-schema en importer zijn een startontwerp, geen gemeten landelijk productiecontract.
- Import_run logt succesvolle transacties; mislukte runs worden alleen naar stderr geschreven omdat hun transactie teruggedraaid wordt.
- Catalogus houdt verdwenen bronrecords voorlopig aan om destructieve interpretatie van gedeeltelijke NDW-data te voorkomen.
- ETag/304-afhandeling zit nog niet in deze CLI. Sprint 0 heeft 304 reeds aangetoond en de latere scheduler moet dit implementeren.
- Statistische EWMA-/uptime-accumulators, grootschalige staging, indexbenchmark en veiligheidsgrenzen voor nationale data zijn nog niet gerealiseerd.
- **Geen productie-/NAS-deployment uitgevoerd.**

## Volgende handeling

Lokale build, PostgreSQL-initialisatie, fixture-import, tweede import, rollback en read-role verifiëren. Daarna eventuele fouten op dezelfde branch herstellen en een echte beperkte landelijke proefimport/benchmark doen voordat Sprint 1 wordt afgerond.

Geen merge of deployment zonder expliciet gebruikersakkoord.

## Windows: PostgreSQL init-script line endings

Bij eerste uitvoering op Windows kan `002_roles.sh: cannot execute: required file not found` optreden als een shellbestand CRLF heeft. De repository bevat nu `.gitattributes` met `*.sh text eol=lf`; een eerder uitgecheckt bestand kan desondanks nog CRLF hebben. Controleer lokaal:

```powershell
python -c "from pathlib import Path; p=Path('db/init/002_roles.sh'); b=p.read_bytes(); print('CRLF:', b.count(bytes([13,10])), 'LF:', b.count(bytes([10])))"
```

Als CRLF groter dan nul is, normaliseer uitsluitend dat lokale bestand:

```powershell
python -c "from pathlib import Path; p=Path('db/init/002_roles.sh'); p.write_bytes(p.read_bytes().replace(bytes([13,10]),bytes([10])))"
```

Een init-script draait alleen bij de **allereerste** initialisatie van het PostgreSQL-volume. Een eerdere mislukte initialisatie laat mogelijk een half ingericht volume achter. Alleen als bevestigd is dat het uitsluitend de nieuwe, ongevulde ChargeWatch-ontwikkeldatabase betreft, mag die specifieke Compose-volume met `docker compose down -v` opnieuw gemaakt worden. **Dit verwijdert alle data in de database van deze Compose-stack**; nooit uitvoeren op een bestaande omgeving of op NAS/productie.
