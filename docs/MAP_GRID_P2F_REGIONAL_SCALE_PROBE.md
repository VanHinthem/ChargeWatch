# ChargeWatch #113 — P2f regionale schaaltest, fase 1

**3 november 2026 — uitsluitend voorbereid, niet gecompileerd of op NAS uitgevoerd.**

P2e was functioneel succesvol voor Oss zoom11: `ids_match=t`, `aggregates_match=t` en een indexgerichte eindaggregatie zonder landelijke `connector`- of `evse`-Seq Scan. De ene warme P2e-run duurde 71,316 ms; dat bewijst **niet** dat dezelfde strategie op grotere gebieden snel genoeg is. We testen daarom gecontroleerd op grotere geografische dekking.

## Deze stap: middelgrote regio, één read-only EXPLAIN

- Vast BBOX **(5.20, 51.60, 5.90, 52.10)** in WGS84.
- **Web Mercator gridLevel 10**, canonical complete-cell ownership door de bestaande grid-expandberekening (celmaat `40075016.68557849/1024` meter).
- Filters `connectorType=ccs2` en `mode=dc`, gegenereerd uit exact dezelfde gelinkte productie-`MapFilter.cs`.
- P2e-gecorreleerde techniekselectie + `LEFT JOIN LATERAL` beschikbaarheidsaggregatie; een `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` als enige dure SELECT.
- `SET ROLE chargewatch_read`, `BEGIN REPEATABLE READ READ ONLY`, **`statement_timeout=5s`**, `lock_timeout=1s`, `jit=off`, `ROLLBACK`, `RESET ROLE`.
- **Bewuste fasering:** geen `EXCEPT`-rasterpariteit en geen tweede baselinequery vóór we weten of deze regiovariant binnen het 5s-budget valt. De exacte Oss-pariteit uit P2e is geen vervanging voor regionale pariteit; die volgt **afzonderlijk** na deze eerste performance-/planinspectie.
- **Nog geen heel-Nederland-run** en geen code-/DB-schemawijzigingen.

## Windows — SQL exporteren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt. Stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-region
if ($LASTEXITCODE -ne 0 -or -not ($sql -match 'P2e selected-location LATERAL availability') -or
    -not ($sql -match 'ST_MakeEnvelope\(5\.20,51\.60,5\.90,52\.10,4326\)') -or
    -not ($sql -match '40075016\.68557849::double precision/1024\.0')) {
    throw 'Onjuiste P2f regio-SQL gegenereerd. Stop.'
}
$sql | Set-Content -Path .\p2f-region.sql -Encoding utf8
scp .\p2f-region.sql hinthem@192.168.2.48:~/p2f-region.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt. Stop.' }
```

## NAS — read-only uitvoeren

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2f-region.sql \
  | tee ~/p2f-region-result.txt
```

**Stop na deze ene run**, ook als hij snel is: stuur het bestand `p2f-region-result.txt` of de fout/timeout eerst terug. Voer niet automatisch nationale varianten uit. De NAS kan tegelijkertijd door collector-/onderhoudswerk worden belast.

## Te analyseren

Per plan: `Execution Time`, `Planning Time`, `geo_locations` werkelijke locaties, `selected` werkelijk gefilterde locaties, uitvoerrijen/cellen, joinstrategie, `idx_evse_active_location` en `idx_connector_active_evse` indexzoekacties, root `shared hit/read`, `temp read/written` en eventuele timeout. Pas na beoordeling plannen we regionale output-/ID-pariteit en een redelijke baseline, en daarna **expliciet afzonderlijk** een landelijke schaalproef. Niet van Oss-performance naar Nederland generaliseren.

PR #114 blijft draft; geen merge, deployment of productiewijziging.
