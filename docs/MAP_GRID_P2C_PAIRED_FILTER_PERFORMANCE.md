# ChargeWatch #113 — P2c afwisselende gefilterde overview-performancebenchmark

**3 november 2026 — voorbereid, nog NIET uitgevoerd.** In P2b is de ID-pariteit tussen oorspronkelijke en geography-first selectie **voor beide filtergevallen bewezen** op de NAS: `ccs2_dc` (34/34, diff 0), `ccs2_dc_minfree1` (30/30, diff 0). De losse performance-uitvoeringen waren onder zeer verschillende cache-/I/O-omstandigheden en laten nog geen stabiele conclusie toe.

## Doel

Vergelijk **precies de bestaande P2a-SQL en P2b-SQL**, zonder derde filterimplementatie of nieuwe schema-/indexwijzigingen, in één afwisselende meetreeks. Het nieuwe `OverviewPairedExporter.cs` roept de al bestaande SQL-exporters aan, die allebei `MapFilter.LocationPredicate(6)` en `AddParameters` uit de **productiebron** gebruiken. De P2b-pariteit-SELECT wordt niet opnieuw tussen de performanceproeven uitgevoerd, zodat die de P2b-cache niet selectief voorverwarmt. De geldige P2b-pariteitsresultaten staan in [P2b](MAP_GRID_P2B_GEOFIRST_FILTER_BENCHMARK.md).

Per filter is de volgorde:

| Run | Query |
|---|---|
| 1 | P2a origineel |
| 2 | P2b geography-first |
| 3 | P2b geography-first |
| 4 | P2a origineel |

Twee vaste scenario's: `ccs2_dc`, daarna `ccs2_dc_minfree1`. **8 uitvoeringplannen** totaal. Iedere `EXPLAIN ANALYZE` draait met eigen `chargewatch_read`-rol, `REPEATABLE READ READ ONLY`-transactie, `statement_timeout=5s`, `lock_timeout=1s`, `jit=off` en `ROLLBACK`. Er staan `\\echo P2c scenario=... run=... variant=...`-labels in het gegenereerde bestand, zodat de acht plannen te onderscheiden zijn. P2c omvat geen API/Android-code of databasewijzigingen.

## Windows: SQL genereren

Voer uit in PowerShell vanuit je lokale checkout, op de ontwerpbranch:

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt. Stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-paired
if ($LASTEXITCODE -ne 0 -or -not ($sql -match 'P2c scenario=ccs2_dc run=1 variant=original') -or -not ($sql -match 'P2c scenario=ccs2_dc_minfree1 run=4 variant=original')) {
    throw 'SQL-generatie is niet geldig. Stop.'
}
$sql | Set-Content -Path .\p2c-paired.sql -Encoding utf8
scp .\p2c-paired.sql hinthem@192.168.2.48:~/p2c-paired.sql
if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt. Stop.' }
```

## NAS: read-only uitvoeren en log bewaren

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2c-paired.sql \
  | tee ~/p2c-paired-result.txt
```

Bij `statement_timeout`, foutmelding of opvallende NAS-belasting stoppen en de fout rapporteren, niet blind timeouts verhogen. Als de collector tijdens de runs veel werk doet, noteer dat. PostgreSQL read-only query's kunnen wel shared buffers dirtied/written rapporteren; dat zijn geen applicatie-`UPDATE`s.

## Beoordeling

- **Eerst** voor elk gelabeld plan `Execution Time`, `Planning Time`, root `Buffers`, daadwerkelijke `selected`-rijaantallen en uiteindelijke vier rastercellen. Een veranderend NDW-snapshot kan aantallen doen verschillen tussen onafhankelijke transacties: dan resultaten niet blind vergelijken.
- Vergelijk met name of de `Parallel Seq Scan on connector` bij CCS2/DC blijft terugkomen; bij CCS2/DC/minFree1 of `idx_evse_active_location` en `idx_connector_active_evse` voor lokale lookups gebruikt worden.
- Bekijk de spreiding tussen de twee runs van elke variant. Deze korte reeks is **geen formele p95, geen cold-cache experiment en geen landelijke performanceclaim**.
- Als bij CCS2/DC de connector-first landelijke scan nog altijd domineert, is een **expliciet gecorreleerde locatiegerichte `EXISTS`-test** de volgende gerichte hypothese, maar uitsluitend met behoud van de **ongewijzigde MapFilter-predicate** en een onafhankelijke ID-pariteitstest. Een geforceerde planneroptie in productie is niet toegestaan zonder bewijs.
- Daarna opschalen naar grote/nationale viewports; een voordeel bij Oss alleen is geen voldoende basis voor een adaptieve planner.

**Status:** code enkel op de ontwerpbranch, nog niet lokaal gebouwd of op NAS uitgevoerd. PR #114 draft. Geen merge, deploy, schema- of productiewijziging.
