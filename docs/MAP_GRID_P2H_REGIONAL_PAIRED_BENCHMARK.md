# ChargeWatch #113 — P2h gepaarde regionale performancebenchmark

**3 november 2026 — voorbereid, NIET lokaal gecompileerd of op NAS uitgevoerd.**

## Aanleiding

P2g regionale correctness gates zijn beide geslaagd binnen het zoom10-gebied `(5.20,51.60,5.90,52.10)`: **exacte ID-pariteit 420=420, 0 afwijkende ID's** en **complete rasterpariteit 12=12 cellen, 420=420 locaties, 0 afwijkende cellen**. P2f testte de geoptimaliseerde route één keer: **866,215 ms** met 22.827 geografische kandidaten, 420 geselecteerde locaties en 86.722 shared reads. Voor een strategie-/API-besluit ontbreekt de oorspronkelijke route op **hetzelfde gebied** en een korte afwisselende reeks.

## Exacte test

`--emit-overview-region-paired` genereert **vier onafhankelijk read-only `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`-statements**, één per `chargewatch_read`-transactie:

| Run | Variant | Herkomst |
|---|---|---|
| 1 | `original` | `OverviewPerformanceExporter.CreateScenario("ccs2_dc")`, uitsluitend Oss-bbox en grid-celmaat vervangen |
| 2 | `indexed` | `OverviewGeoFirstExporter.CreateMediumRegionCcs2Dc()` (precies P2f) |
| 3 | `indexed` | exact dezelfde SQL als run 2 |
| 4 | `original` | exact dezelfde SQL als run 1 |

Alle runs gebruiken **de productie-`MapFilter.cs`** voor `connectorType=ccs2&mode=dc`, dezelfde regionale bbox, dezelfde Web Mercator gridLevel10-celgeometrie, dezelfde bestaande benchmark-COUNT-statusfuncties en de bestaande ruimtelijke `ST_Intersects`-voorwaarden. Beide routes aggregeren na filtering dezelfde algemene actieve EVSE's per locatie, ongeacht technisch filter. De enige variatie is de queryopbouw. Er zijn geen nieuwe filter- of statusregels.

### Veiligheidsgrenzen

- Elke variant: `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `SET LOCAL statement_timeout='5s'`, `SET LOCAL lock_timeout='1s'`, `SET LOCAL jit=off`, `ROLLBACK`, `RESET ROLE`.
- De P2g-pariteit is reeds geslaagd; dus **geen extra warm-up/pariteitsquery vóór de vier EXPLAIN's**. Dit voorkomt asymmetrische opwarming door de correctness-queries.
- Deze vier runs zijn exploratief, **geen p95**, geen garantie van gelijke diskcache, geen landelijke schaalclaim. Een timeout is een nuttige grensmeting: niet blind verhogen.
- **Geen nationale uitvoering** en geen DB/schema/index/NDW-/API-/Android-wijzigingen.

## Windows: genereren en controleren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-region-paired
if ($LASTEXITCODE -ne 0 -or
    -not ($sql -match 'P2h region run=1 variant=original') -or
    -not ($sql -match 'P2h region run=4 variant=original') -or
    -not ($sql -match 'ST_MakeEnvelope\(5\.20,51\.60,5\.90,52\.10,4326\)') -or
    ($sql -split 'EXPLAIN \(ANALYZE, BUFFERS, SETTINGS\)').Count -ne 5) {
    throw 'P2h SQL-generatie ongeldig; stop.'
}
$sql | Set-Content -Path .\p2h-region-paired.sql -Encoding utf8
scp .\p2h-region-paired.sql hinthem@192.168.2.48:~/p2h-region-paired.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt; stop.' }
```

## NAS: één runreeks

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2h-region-paired.sql \
  | tee ~/p2h-region-paired-result.txt
```

Bij timeout/PSQL-fout geen extra runs of nationale benchmark starten. Bewaar de volledige uitvoer met `P2h region run=...`-markeringen en stuur `p2h-region-paired-result.txt` terug.

## Beoordeling

Per run: `Execution Time`, `Planning Time`, root `Buffers shared hit/read/written`, `geo_locations` en `selected` aantallen, aantal outputcellen, `Parallel Seq Scan on connector` of `Seq Scan on evse`, aantallen lokale EVSE-/connector-indexzoekacties, eventuele `temp read/written`. Beslissen op queryplan + herhaalbaarheid en I/O-kosten, **niet** uitsluitend de snelste run. De nationale gebruikslast (lagere gridlevels; grotere geografische kandidatenset) blijft een afzonderlijk nog niet gevalideerd scenario.

PR #114 draft; geen merge of deployment.
