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


## Eerste echte NAS-queryplan ontvangen — 3 november 2026

Gebruiker leverde één compleet `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`-plan aan. Herleid uit de werkelijke planpredicaten: scenario **`ccs2_dc_minfree1`** (`ccs2`, `dc` en `minFree>=1`). De ongefilterde en uitsluitend CCS2/DC-plannen zijn **niet** meegestuurd en mogen niet als uitgevoerd worden geregistreerd.

| Gegeven | Werkelijke uitvoer |
|---|---|
| Execution Time | **135,861 ms** |
| Planning Time | **3,423 ms** |
| Selected locaties | **30** |
| Grid-resultaatrijen | **4** |
| Eind-joinrows (alle actieve EVSE's op geselecteerde locaties) | **235** |
| Root buffers | `shared hit=34057 read=23497 written=222` |
| Connector-scan | `Parallel Seq Scan on connector c_1`, 2 workers, **6.473** technisch passende connectorrows; `shared hit=509 read=23331` |
| EVSE-id-lookups | `evse_pkey`: **6.467 index searches** |
| Kandidaatlocaties na connector/EVSE | **1.637** distinct `location_id` |
| Location lookups | `charging_location_pkey`: **1.637 index searches**; geografische filter pas op deze stap |
| Extra minFree-SubPlan | **34** locatieruns; actieve EVSE-index 34 searches, connector-index 168 searches |
| Geselecteerde-locatie availability-join | `idx_evse_active_location`: 30 index searches |
| Disk spill | Aggregates Batches=1; geen expliciete temp disk-spill zichtbaar |

**Observatie:** bij de combinatie met technisch filter en minFree kiest PostgreSQL een connector-first plan dat de actieve connectoren in heel Nederland scant, nog voordat Oss-geografie wordt toegepast. Het is geen fout in `MapFilter` of bewijs van filterincorrectheid, maar een concrete planner-/querystructuurkandidaat voor optimalisatie. De SQL-benchmark is bewust een handmatig opgestelde overview-SELECT met de **echte MapFilter-predicate**; in productie blijft parameterbinding verplicht.

**Vervolg:** eerst de twee overige P2a-scenario's (ongefilterd en CCS2/DC zonder minFree) uit het al gegenereerde SQL-bestand beoordelen, zodat we kunnen isoleren welk onderdeel van het plan de cost veroorzaakt. Daarna hooguit één gerichte read-only vergelijking met een afgedwongen geografische `MATERIALIZED` selectie vóór het filterpredicaat. Ook dat moet dezelfde `MapFilter.LocationPredicate`-string en dezelfde ID-set behouden: geen tweede filterimplementatie. Geen schema- of API-wijzigingen, merge of deploy.


## P2a — alle drie de uitgevoerde scenario's (3 november 2026)

Gebruiker heeft de **volledige SQL-run als bijlage** aangeleverd. Alle drie `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`-plannen zijn uitgevoerd en leverden vier rastercellen op:

| Scenario | geselecteerde locaties | gridrijen | Execution Time | Planning Time | Root shared hit/read/written |
|---|---:|---:|---:|---:|---|
| unfiltered | **2.101** | **4** | **214,024 ms** | 24,262 ms | 1.062 / 30.056 / 5.307 |
| ccs2_dc | **34** | **4** | **160,011 ms** | 3,516 ms | 26.583 / 30.001 / 2.572 |
| ccs2_dc_minfree1 | **30** | **4** | **137,799 ms** | 3,614 ms | 33.998 / 23.531 / 132 |

**Unfiltered:** `Parallel Bitmap Heap Scan` op `charging_location` gebruikt `ix_charging_location_active_position`, gevolgd door een `Seq Scan` over **216.299 actieve EVSE's**, Hash Right Join (5.099 rijresultaten), per-location HashAggregate 2.101, eind GroupAggregate 4.

**CCS2/DC:** `Parallel Seq Scan on connector` vindt **6.473** connectorrijen (23.838 shared reads op connector), gevolgd door **6.467** `evse_pkey` lookups; **1.637** kandidaat-locaties na EVSE-deduplicatie, daarna **1.637** `charging_location_pkey` lookups met geografische predicate; uiteindelijk **34** locaties. `idx_evse_active_location` gebruikt voor 34 geselecteerde locaties (244 EVSE-joinrijen). Het plan is **connector-first**, niet geography-first.

**CCS2/DC/minFree=1:** parallel connector Seq Scan vindt wederom 6.473 connectorrijen (23.391 connector-reads), **6.467** EVSE-PK lookups, 1.637 locatie-lookupkandidaten, daarna 34 minFree-subplan-aggregaties en **30** geselecteerde locaties; eind-availability-join **235** rijen. Het plan is eveneens connector-first. Het plan is uitvoerbaar, maar er is nog **geen bewijs van een betere querystrategie** en geen garantie dat de planner bij nationaal bereik hetzelfde doet.

**Hypothese voor de eerstvolgende, gerichte performanceproef:** maak een `geo AS MATERIALIZED`-CTE die Oss-locaties met de bestaande GiST-geografische selectie kiest, pas vervolgens de **ongewijzigde productie-`MapFilter.LocationPredicate`** op de `geo`-alias toe. Vergelijk op één read-only snapshot de exacte geselecteerde IDs met de originele query (EXCEPT in beide richtingen), en pas daarna het `EXPLAIN ANALYZE`-plan. Dit blijft een SQL-optimizer-experiment, **geen beslissing om productie-SQL te wijzigen**. Geen schema-/NDW-/statuswijzigingen, merge of deploy.
