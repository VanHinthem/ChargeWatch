# ChargeWatch #113 — P2a gefilterde complete-cell overview benchmark

**3 november 2026. Voorbereid, nog niet uitgevoerd.** Dit is de eerstvolgende stap na P1a–P1d: meten van een complete server-side overview-`SELECT`, met een **echt door de bestaande productie-`MapFilter.cs` gegenereerd locatiepredicaat**. De bestaande A1/A2/A3-runs gebruikten geen kaartfilters. P2a gebruikt niet opnieuw een handgeschreven kopie van die voorwaarden.

## Wat de proef doet

- Een geïsoleerde C# harness (`tools/MapFilterQ4Probe/OverviewPerformanceExporter.cs`) compileert en gebruikt de productie-`MapFilter.cs` rechtstreeks.
- Hij genereert drie vaste read-only SQL-scenario's voor het Oss zoom-11-grid met **volledig gedekte rastercellen**: `unfiltered`, `ccs2_dc` en `ccs2_dc_minfree1`.
- Locaties worden geselecteerd via `MapFilter.LocationPredicate(6)` plus `MapFilter.AddParameters`. Voor handmatig uit te voeren SQL zijn **uitsluitend bekende gevalideerde text-arrayfilterwaarden** als PostgreSQL-literals gerenderd. **Dit is een benchmarkexport, geen API-productieparameterbinding.**
- De query telt daarna over **alle actieve operationele EVSE's van de geselecteerde locaties**, niet alleen de passende connectors. De groen/rood/grijs-indeling is de reeds gebruikte **voorlopige simpele benchmarkclassifier** en mag geen nieuwe domeinsemantiek worden.
- Deze P2a meet `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` en outputgroepsaantallen; hij bewijst **nog niet** een definitief API-responsecontract, nationale performance of volledige filtermatrix. Q4c heeft de filter-ID-pariteit afzonderlijk al aangetoond.

## Windows — bouwen en SQL genereren

Voer uit vanuit je lokale checkout op de juiste branch:

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; geen SQL uploaden.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-perf
if ($LASTEXITCODE -ne 0 -or -not ($sql -match 'ChargeWatch #113 actual overview query benchmark:')) {
    throw 'Geen geldige overview SQL gegenereerd; geen SQL uploaden.'
}
$sql | Set-Content -Path .\p2a-overview-generated.sql -Encoding utf8
scp .\p2a-overview-generated.sql hinthem@192.168.2.48:~/p2a-overview-generated.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt.' }
```

**Let op:** gebruik `dotnet run --no-build` pas **na** een geslaagde build en schrijf nooit compiler-/CLI-foutoutput rechtstreeks naar een `.sql`-bestand.

## NAS — read-only uitvoeren

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2a-overview-generated.sql
```

De uitvoer bevat **drie afzonderlijke EXPLAIN-plannen**. Iedere run schakelt naar `chargewatch_read`, start `REPEATABLE READ READ ONLY`, zet `statement_timeout=5s`, `lock_timeout=1s`, `jit=off`, sluit met `ROLLBACK` en `RESET ROLE`. Er worden geen data of indexen gewijzigd.

## Rapporteren / interpreteren

Per scenario: `Execution Time`, `Planning Time`, `selected`-CTE werkelijke locatie-rijen, eind-`GroupAggregate/HashAggregate` rijen, `Buffers`, eventuele `temp written/read`, joinstrategie, index-/seq-scans en timeouts. Verwacht dat de **ongefilterde** selectie rond 2.101 locaties en 4 gridcellen bevat zolang NDW-data stabiel is; gefilterde aantallen niet voorspellen. De inhoudelijke locatieselectie is Q4-getest, maar snelheid en SQL-planner van deze gecombineerde query zijn nieuw en **nog niet** gemeten. Een `statement_timeout` is een te rapporteren resultaat, niet een reden om blind timeouts of productie-instellingen op te hogen.

## Daarna

Als Oss binnen het budget en zonder onverwachte planproblemen draait, volgen de grotere zoom-8-regio en nationale schaal **in oplopende stappen** met dezelfde echte MapFilter-generator. Neem geen nationale benchmark of cachingstrategie aan zonder aparte meetgegevens. Voor de echte API moeten de huidige bestaande NDW-/availability-/kwaliteitsregels worden hergebruikt, geen tweede classifier. PR #114 draft; geen merge, deploy of database-/productiecodewijzigingen.
