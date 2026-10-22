# Sprint 2 — automatische NDW-verversing

**Scope:** periodieke, foutveilige verversing van de landelijke NDW OCPI Locations- en Tariffs-feeds bovenop de gevalideerde Sprint 1 bulkimport.

## Gedrag

De importer heeft twee nieuwe modi:

- `--watch`: blijft draaien en controleert de Locations-feed standaard iedere 5 minuten en Tariffs iedere 60 minuten.
- `--watch-once`: voert één conditionele controle van beide feeds uit en stopt daarna. Dit is de aanbevolen lokale validatiemodus.

Per feed worden `ETag` en `Last-Modified` bewaard in `refresh-state.json`. Bij `304 Not Modified` wordt geen database-import uitgevoerd als geen van beide feeds veranderd is.

Bij een `200 OK` wordt de nieuwe gzip eerst als uniek `.pending-...` bestand opgeslagen. De bestaande transactionele `--bulk` import draait rechtstreeks tegen die pending bron en de laatst geldige cache van de andere feed. Pas nadat de database-import succesvol commit, wordt het pending bestand de nieuwe vaste cache. Een HTTP-, gzip-, JSON- of importfout laat daardoor zowel de database als de laatst geldige broncache ongemoeid.

Staleness wordt bepaald aan de hand van de laatste succesvolle **Locations**-controle; een succesvolle tariefcheck kan een falende statusfeed dus niet maskeren.

## Standaardbronnen

- Locations: `https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz`
- Tariffs: `https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz`

## Configuratie

| Variabele | Standaard | Doel |
|---|---:|---|
| `CW_IMPORT_CONNECTION` | verplicht | PostgreSQL-connectionstring voor `chargewatch_import` |
| `CW_NDW_CACHE_DIR` | `<app>/ndw-cache` | vaste broncache + refresh-state |
| `CW_NDW_LOCATIONS_URL` | NDW Locations URL | override voor test/lokaal |
| `CW_NDW_TARIFFS_URL` | NDW Tariffs URL | override voor test/lokaal |
| `CW_NDW_LOCATIONS_INTERVAL_MINUTES` | `5` | controlefrequentie Locations |
| `CW_NDW_TARIFFS_INTERVAL_MINUTES` | `60` | controlefrequentie Tariffs |
| `CW_NDW_STALE_AFTER_MINUTES` | `15` | waarschuwing als Locations niet succesvol gecontroleerd is |
| `CW_NDW_MIN_LOCATIONS` | `50000` | veiligheidsgrens vóór publicatie |
| `CW_NDW_HTTP_TIMEOUT_SECONDS` | `120` | HTTP-timeout |

Alle numerieke waarden moeten positieve gehele getallen zijn.

## Lokale validatie

Gebruik eerst een aparte test/benchmarkdatabase; verwijder bestaande volumes of databases niet.

```powershell
$env:CW_IMPORT_CONNECTION = "Host=localhost;Port=5432;Database=chargewatch_benchmark;Username=chargewatch_import;Password=<password>"
$env:CW_NDW_CACHE_DIR = "$PWD\.local\ndw-cache"

dotnet build .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj
dotnet run --project .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj -- --watch-once
```

Verwacht bij de eerste run:
1. beide feeds geven `200`;
2. beide downloads blijven pending tijdens validatie;
3. de bestaande bulkimport commit;
4. daarna verschijnen `locations.json.gz`, `tariffs.json.gz` en `refresh-state.json`.

Voer hetzelfde commando direct nogmaals uit. Als NDW niet gewijzigd is, verwacht:
- `304` voor beide feeds;
- melding `database import skipped`;
- geen extra `import_run`-record.

Controleer daarna bijvoorbeeld:

```powershell
docker compose exec -T db psql -U chargewatch_owner -d chargewatch_benchmark -c "SELECT id, started_at, completed_at, source_name, status, location_count, evse_count, connector_count FROM import_run ORDER BY id DESC LIMIT 5;"
```

Voor een foutpad kan tijdelijk een van de URL-variabelen naar een ongeldige of niet-gzip testbron wijzen. De run moet falen zonder nieuwe succesvolle `import_run`, terwijl de bestaande vaste cachebestanden behouden blijven.

## Continuous mode

Na lokale validatie kan dezelfde executable langdurig draaien met:

```powershell
dotnet run --project .\src\ChargeWatch.Importer\ChargeWatch.Importer.csproj -- --watch
```

`Ctrl+C` beëindigt de scheduler gecontroleerd. Een mislukte cyclus stopt continuous mode niet; na het geconfigureerde interval wordt opnieuw geprobeerd.

## Bewuste grenzen van Sprint 2

- Geen NAS-/productiedeployment.
- Geen REST API of Android-code.
- Geen occupancy-/uptimestatistieken.
- Geen schemawijzigingen.
- Geen GitHub Actions-workflow; build/validatie is voor deze PR lokaal uit te voeren.
- NDW-licentie/attributie blijft een open distributiepunt.
