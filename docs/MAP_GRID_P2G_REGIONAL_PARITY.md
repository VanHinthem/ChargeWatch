# ChargeWatch #113 — P2g regionale ID- en rasteraggregaatpariteit

**3 november 2026 — voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd.**

De P2f CCS2/DC-regioproef op bbox `(5.20,51.60,5.90,52.10)`, Web Mercator gridLevel10, leverde in één read-only `EXPLAIN ANALYZE` **22.827 geografische locaties**, **420 gefilterde locaties**, **12 niet-lege cellen** en **866,215 ms** execution. De grote indexlookup-aantallen zijn gemeten; P2f heeft nog **geen regionale correctheidspariteit** aangetoond.

## Doel en reikwijdte

De CLI-optie `--emit-overview-region-parity` genereert een **correctheidstest, zonder extra EXPLAIN-performance-run**. Hij hergebruikt de bestaande `OverviewGeoFirstExporter`, met de **gelinkte productie-`MapFilter.cs`** en exact de P2f-bbox/gridLevel/filterwaarden.

Deze ene read-only `REPEATABLE READ`-transactie bevat:

1. **Regionale geselecteerde locatie-ID's:** de oorspronkelijke ruimtelijke `charging_location l`-query met `MapFilter.LocationPredicate(6)` versus de locatiegecorreleerde P2f-selectie, via `EXCEPT` in beide richtingen. Vereist: `original_locations=geofirst_locations`, `differing_ids=0`, `ids_match=t`.
2. **Volledige regionale rasteroutput:** oorspronkelijke P2d-availability-join versus de P2e/P2f `LEFT JOIN LATERAL`-variant, beide met **dezelfde P2f-gecorreleerde geselecteerde locaties en dezelfde bestaande benchmark-COUNT-expressies**, op één statement/snapshot. `EXCEPT` vergelijkt `cx,cy,location_count,locations_with_free,locations_with_no_free,locations_unknown`. Vereist: `baseline_cells=indexed_cells`, `baseline_locations=indexed_locations`, `differing_cell_rows=0`, `aggregates_match=t`.
3. **Geen EXPLAIN ANALYZE** in deze uitvoer. Daarmee scheiden we regionale correctheid van de benchmark en voorkomen we onnodige NAS-belasting.

**Belangrijke afbakening:** de tweede vergelijking is een **join-/aggregatiepariteitstest** met dezelfde geselecteerde IDs als bron. De eerste vergelijking bevestigt onafhankelijk de ID-selectie tegenover het oorspronkelijke `MapFilter`-filter. Dit is nog geen definitief productie-availabilitycontract; de voorlopige benchmarkclassificatie wordt niet gewijzigd.

## Windows: nieuwste branch bouwen en SQL exporteren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-region-parity
if ($LASTEXITCODE -ne 0 -or
    -not ($sql -match 'P2e selected-location LATERAL availability') -or
    -not ($sql -match 'ST_MakeEnvelope\(5\.20,51\.60,5\.90,52\.10,4326\)') -or
    -not ($sql -match '40075016\.68557849::double precision/1024\.0') -or
    -not ($sql -match 'aggregates_match') -or
    ($sql -match 'EXPLAIN \(ANALYZE')) {
    throw 'P2g SQL is ongeldig of bevat onverwachte EXPLAIN; stop.'
}
$sql | Set-Content -Path .\p2g-region-parity.sql -Encoding utf8
scp .\p2g-region-parity.sql hinthem@192.168.2.48:~/p2g-region-parity.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt; stop.' }
```

## NAS: één read-only correctness-run

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2g-region-parity.sql \
  | tee ~/p2g-region-parity-result.txt
```

De gegenereerde SQL gebruikt `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `statement_timeout='5s'` per statement, `lock_timeout='1s'`, `jit=off`, afsluitend `ROLLBACK` en `RESET ROLE`. Een timeout is een testresultaat: **niet automatisch verlengen of de proef herhalen**.

## Interpretatie / vervolg

Verwacht niet dat de cijfers exact `420/12` blijven: NDW-data kan tussen P2f en P2g veranderen. **Vergelijk de twee zijden van elke P2g-vergelijking binnen hetzelfde snapshot**, niet enkel met de vorige run.

- Bij beide PASS-controles is regionale pariteit aangetoond voor de huidige CCS2/DC-fixture. Daarna pas een afzonderlijke originele `MapFilter`-overview-EXPLAIN op identieke bbox en de performanceroutes afwisselend meten.
- Bij `ids_match=f` of `aggregates_match=f`: onderzoek de afwijkende IDs/celrijen eerst, geen snelheidsbesluit.
- Bij timeout: beoordeel hoeveel belasting de dubbele vergelijkingsquery veroorzaakt; geen landelijke test starten.
- **Nog geen landelijke proef, geen nieuwe indexen, geen productie-API-/Android-code, geen NDW-/Q5-statusherontwerp, geen merge of deploy.** PR #114 blijft draft.
