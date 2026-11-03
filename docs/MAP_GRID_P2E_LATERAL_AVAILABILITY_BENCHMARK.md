# ChargeWatch #113 — P2e: geselecteerde-locatie EVSE-indexjoin en aggregatiepariteit

**3 november 2026 — voorbereid, niet lokaal gebouwd of op NAS uitgevoerd.** P2d bewees correcte locatie-ID-pariteit (34/34, verschil 0) en elimineerde de landelijke *connector*-scan. In de P2d-overview kwam echter nog steeds een **landelijke EVSE Seq Scan** voor (216.524 actieve EVSE-rijen, ruim 100 ms scan). Dit is één gerichte performanceproef, geen nieuwe status-/NDW-semantiek.

## Aanpassing

Het nieuwe CLI-commando `--emit-overview-lateral-availability` gebruikt **dezelfde** `OverviewGeoFirstExporter`-bron en dezelfde productie-`MapFilter.LocationPredicate(6)`. De P2d geo-first + gecorreleerde technische filterselectie blijft ongewijzigd. Alleen de **laatste** availability-join wordt in de P2e-variant vervangen:

```sql
-- Alleen illustratie van de veranderde join, niet zelfstandig uitvoerbaar:
FROM selected s
LEFT JOIN LATERAL (
  SELECT e.id, e.status FROM evse e
  WHERE e.location_id=s.id AND e.is_active
  OFFSET 0
) e ON true
GROUP BY s.id,s.cx,s.cy
```

Dezelfde `count(... ) FILTER (...)`-expressies uit de P2d-query blijven behouden, zodat rood/groen/onbekend niet opnieuw wordt gedefinieerd. `OFFSET 0` is uitsluitend een geteste plannerbarrièrekandidaat, geen gekozen productieimplementatie. De query telt per locatie **alle actieve EVSE's** (niet slechts CCS2-connectoren), conform de eerdere P2d-overview-scope.

## Verplichte validatie in één read-only transactie

De generator schrijft:

1. **Locatie-ID-EXCEPT-pariteit:** P2d oorspronkelijke locatiepredicate versus geo-first-gecorreleerde `MapFilter`; `differing_ids=0`, `ids_match=t`.
2. **Volledige rasteraggregaatpariteit:** de eerdere P2d-SELECT (landelijke EVSE-scan) en de P2e-SELECT (LATERAL-indexzoekacties) draaien als `baseline` en `indexed` in **één SQL-statement op hetzelfde snapshot**. Dubbelzijdig `EXCEPT` vergelijkt **elke cel** en alle kolommen `cx, cy, location_count, locations_with_free, locations_with_no_free, locations_unknown`. Vereist: `baseline_cells=indexed_cells`, `baseline_locations=indexed_locations`, `differing_cell_rows=0`, `aggregates_match=t`.
3. **Performanceplan** `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` van P2e-indexlookup. Controleer vooral of de landelijke `Seq Scan on evse` verdwenen is en `idx_evse_active_location` wordt gebruikt. Ook eventuele afwijkende rijaantallen, buffers en timeouts rapporteren.

**Bij mislukte pariteit niet doorgaan met implementatiebesluiten**, ook als de query sneller lijkt. Het script zelf voert drie SELECT-statements uit; bij pariteitsfout wordt de derde SELECT momenteel niet automatisch overgeslagen. Beoordeel de resultaten expliciet.

## Windows — SQL genereren

Op de lokale ontwerpbranch in PowerShell:

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt. Stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-lateral-availability
if ($LASTEXITCODE -ne 0 -or -not ($sql -match 'P2e selected-location LATERAL availability')) {
    throw 'P2e SQL-generatie mislukt. Stop.'
}
$sql | Set-Content -Path .\p2e-lateral-availability.sql -Encoding utf8
scp .\p2e-lateral-availability.sql hinthem@192.168.2.48:~/p2e-lateral-availability.sql
if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt. Stop.' }
```

## NAS — read-only uitvoeren en resultaat bewaren

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2e-lateral-availability.sql \
  | tee ~/p2e-lateral-availability-result.txt
```

Er wordt met `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `statement_timeout=5s`, `lock_timeout=1s`, `jit=off`, `ROLLBACK` en `RESET ROLE` gewerkt. De twee grote rastervergelijkingen in één snapshot kunnen de uitvoering en bufferbelasting van de laatste `EXPLAIN` beïnvloeden; vergelijk latentie pas na aparte herhaling zonder pariteitsvoorloop.

## Interpretatie en vervolgstap

Bij zowel exacte pariteit als aantoonbaar lokale EVSE-indexscans hebben we **een werkende kleine-Oss-kandidaat**, niet automatisch een landelijke winnaar. Herhaal daarna alleen indien nuttig in afwisselende volgorde met P2d/P2e; test vervolgens grotere en landelijke volledige gridcellen, inclusief on-gefilterde en andere filters, vóór API-besluit. Q5 NDW-/statusdefinities blijven geparkeerd en worden niet heropend.

**Status:** P2e-code alleen op ontwerpbranch. Geen API-/Android-/DB-migratie-/deployment-/mergeactie. PR #114 draft. Build en NAS-queryuitvoering nog niet door assistent bevestigd.
