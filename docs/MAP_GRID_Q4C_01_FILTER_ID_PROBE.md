# ChargeWatch #113 — Q4c-01 daadwerkelijke filter-ID-setcontrole (CCS2 + DC)

**3 november 2026. Status: generator toegevoegd; niet lokaal gebouwd of op NAS uitgevoerd.** Q4c-00 is geslaagd: 98 viewport-ID's, 419 volledige rasterlocaties, 98 geclipte raster-ID's en `differing_ids=0`.

## Waarom de generator gebruiken?

De kleine standalone .NET 10-harness [tools/MapFilterQ4Probe](../tools/MapFilterQ4Probe/Program.cs) compileert de **bestaande productie-`MapFilter.cs`** mee. De nieuwe exportoptie maakt twee read-only SELECT-selecties met `LocationPredicate(5)` respectievelijk `LocationPredicate(6)`, roept in beide gevallen `AddParameters()` aan en zet uitsluitend de vooraf bekende, gevalideerde testwaarden om naar ge-escapete PostgreSQL `text[]`-constanten voor handmatige uitvoering. Dit is een tijdelijke *testexport* om vanaf een NAS zonder .NET te valideren; de uiteindelijke productieroute moet Npgsql-parameters gebonden houden, niet literals renderen.

De query controleert de symmetrische verschilset van geselecteerde `charging_location.id`-waarden, inclusief canoniek Web Mercator-celownership en clipping naar dezelfde kleine viewport. Beide CTE's worden onder één `REPEATABLE READ READ ONLY`-snapshot uitgevoerd. Dit test de werkelijk door `MapFilter` gegenereerde CCS2+DC-predicaten, maar nog niet alle Q4-filtervarianten.

## Stap 1 — lokaal op Windows, PowerShell

Vanaf `C:\GITHUB\ChargeWatch`:

```powershell
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c01 |
  Set-Content -Path q4c01-generated.sql -Encoding utf8
Get-Content q4c01-generated.sql -TotalCount 8
```

Controleer dat de gegenereerde file met de Q4c-01-commentaarregels en daarna `SET ROLE chargewatch_read;` begint. De export schrijft geen verbindinggegevens en opent geen database.

## Stap 2 — kopieer de gegenereerde SQL naar de NAS

```powershell
scp .\q4c01-generated.sql hinthem@HINNAS:/tmp/chargewatch-q4c01.sql
```

`HINNAS` is de eerder gebruikte NAS-hostnaam; vervang deze indien nodig door de SSH-host die lokaal werkt. **Het bestand is uitsluitend een testscript op /tmp; de database wordt hiermee niet gewijzigd.** Doe deze stap alleen als SSH/scp reeds is ingericht.

## Stap 3 — NAS-shell, read-only uitvoeren

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < /tmp/chargewatch-q4c01.sql
```

De SQL begint met `SET ROLE chargewatch_read`, opent een `REPEATABLE READ READ ONLY`-transactie, stelt `statement_timeout='5s'` en `lock_timeout='1s'` in en eindigt met `ROLLBACK; RESET ROLE;`. Stop bij een fout; rapporteer de foutmelding in plaats van opnieuw te proberen met langere timeouts.

## Acceptatie

Verwacht `differing_ids=0`, `viewport_unique=t`, `overview_unique=t` en `ids_match=t`. De gefilterde viewport-ID-telling kan lager zijn dan 98. De volledige grid-selectie kan groter zijn dan de viewport, omdat alleen die laatste naar de kleine bbox wordt geclipt. Als er nul matches zijn, is de test formeel gelijk maar heeft deze weinig positieve selectiekracht: dan kiezen we een kleine bbox met werkelijke CCS2/DC-matches voordat we de variant afvinken.

**Geen testsucces geclaimd vóór werkelijke gebruikersuitvoer.** Geen API-/Android-implementatie, schemawijziging, deployment of merge. PR #114 blijft draft.
