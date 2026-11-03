# ChargeWatch #113 — P2b gefilterde overview: geography-first versus bestaande optimizerkeuze

**3 november 2026. Gereed als read-only proef; nog niet gebouwd of uitgevoerd.** Aansluitend op de P2a-uitvoer (ongefilterd 214,024 ms/2.101 locaties; CCS2+DC 160,011 ms/34 locaties; CCS2+DC+minFree1 137,799 ms/30 locaties).

## Hypothese

Bij de technische filters begon PostgreSQL met een **landelijke Parallel Seq Scan van connectoren** (6.473 matches) en pas vervolgens een geografische toets van 1.637 locaties. Voor een klein viewport zou het goedkoper kunnen zijn om de ~2.101 geografisch relevante locaties **eerst te materialiseren**, daarna pas hetzelfde `MapFilter.LocationPredicate(6)` uit te voeren. De voorgestelde proef verandert **geen filter- of beschikbaarheidslogica**.

De nieuwe geïsoleerde `OverviewGeoFirstExporter.cs` gebruikt dezelfde gelinkte productie-`MapFilter.cs`. Voor `ccs2_dc` en `ccs2_dc_minfree1` genereert hij elk:
1. Een **ID-pariteitstest op één read-only PostgreSQL-snapshot**: oorspronkelijk predicate op `charging_location l` versus predicate op `geo_locations AS MATERIALIZED` (alias eveneens `l`). Via `EXCEPT` in beide richtingen wordt `differing_ids=0` en `ids_match=t` vereist.
2. Een `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` van de geography-first overview met dezelfde eenvoudige, **niet-definitieve** benchmark-statusclassifier als P2a.

De locatie-CTE bevat `id,operator_name,cx,cy`, voldoende voor de bestaande MapFilter-generatie: `l.id` en `l.operator_name`. Geen gekopieerde filtersemantiek.

## Windows: bouwen en script genereren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project='tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; niets naar NAS sturen.' }

$sql=dotnet run --no-build --project $project -- --emit-overview-geofirst
if ($LASTEXITCODE -ne 0 -or -not ($sql -match '^-- P2b geography-first')) {
    throw 'Geen geldige P2b SQL gegenereerd.'
}
$sql | Set-Content -Path .\p2b-geofirst.sql -Encoding utf8
scp .\p2b-geofirst.sql hinthem@192.168.2.48:~/p2b-geofirst.sql
if ($LASTEXITCODE -ne 0) { throw 'Bestandsoverdracht mislukt.' }
```

## NAS: veilig uitvoeren

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2b-geofirst.sql
```

De twee proeven zijn read-only met `SET ROLE chargewatch_read`, `BEGIN REPEATABLE READ READ ONLY`, `statement_timeout=5s`, `jit=off`, `ROLLBACK` en `RESET ROLE`. De generator gebruikt alleen vaste, door productie-`MapFilter.TryParse` gevalideerde arraywaarden als SQL-literals voor handmatige performanceproeven; productie-API-parameterbinding blijft onaangetast.

## Acceptatie en besluit

- **Eerst** voor beide gevallen `ids_match=t`, `differing_ids=0`; bij verschil het experiment afkeuren, ongeacht snelheid.
- Vergelijk `Execution Time` met P2a alleen als eerste indicatie; losse runs hebben verschillende caches en NAS-/collectorbelasting.
- Kijk vooral of de `geo_locations`-CTE eerst de ruimtelijke GiST-index benut en de landelijke connector-`Seq Scan` verdwijnt; rapporteer `Buffers`, aantallen connector/EVSE-lookups, `temp`-I/O en eventuele timeouts.
- Als allebei sneller en correct lijken, gecontroleerd herhalen in omgekeerde volgorde en daarna grotere regio; nooit een lokale winst automatisch naar nationale zoomniveaus extrapoleren.
- De Q5/NDW-availability-logic wordt **niet** opnieuw ontworpen; bestaande productielogica blijft bron van waarheid. De proef implementeert **geen** nieuwe API, DB-migratie of Android-code.

PR #114 blijft draft. Geen merge of NAS-deployment.
