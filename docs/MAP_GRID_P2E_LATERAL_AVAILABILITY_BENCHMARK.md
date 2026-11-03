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


## Werkelijke NAS-resultaten — 3 november 2026 (P2e)

Gebruiker leverde het volledige `p2e-lateral-availability-result.txt` aan. **De SQL is uitgevoerd met `chargewatch_read` in read-only transactie, zonder gemelde fout of timeout.**

### Beide pariteitscontroles geslaagd

| Check | Baseline | P2e indexed | Differing | Resultaat |
|---|---:|---:|---:|---|
| Locatie-ID's (`ccs2_dc`) | 34 | 34 | **0** | **`ids_match=t`** |
| Complete rasteraggregaten | 4 cellen / 34 locaties | 4 cellen / 34 locaties | **0 verschillende celrijen** | **`aggregates_match=t`** |

De per-cel-pariteit betreft de complete `SELECT`-uitvoer: `cx`, `cy`, `location_count`, `locations_with_free`, `locations_with_no_free` en `locations_unknown`. Dezelfde bestaande benchmarkstatus-COUNT-expressies zijn gebruikt; dit is **geen** wijziging aan NDW-/productiestatuslogica.

### Uitvoeringsplan P2e

| Maatstaf | Resultaat |
|---|---:|
| `Execution Time` | **71,316 ms** |
| `Planning Time` | **2,951 ms** |
| Shared root buffers hit | **33.513** |
| Shared root buffers read | **0** |
| `geo_locations` | **2.101 locaties**; `ix_charging_location_active_position` (GiST) |
| `selected` | **34 locaties**; `Nested Loop` met `MapFilter` uit productiebron |
| Technische filter: active EVSE indexzoekacties | **2.101** |
| Technische filter: active connector indexzoekacties | **4.838** |
| Laatste availability-join | **`Nested Loop Left Join`** |
| Laatste EVSE-indexzoekacties | **34** via `idx_evse_active_location` |
| Laatste EVSE-joinrijen | **244** |
| Eindresultaat | **4 rastercellen** |

**Belangrijk:** de landelijke `Parallel Seq Scan on connector` uit P2c is in P2e afwezig en ook de landelijke `Seq Scan on evse` uit P2d is verdwenen. De laatste EVSE-join rapporteert `Index Scan using idx_evse_active_location` met 34 indexzoekacties en `Buffers: shared hit=298`. In deze sample is de finale beschikbaarheidsaggregatie dus niet de bottleneck; de locatiegecorreleerde filterselectie blijft het grootste deel van de runtime verbruiken (~69,5 ms tot en met `selected`).

### Nauwkeurige vergelijking met P2d

P2d losse uitvoering: **160,477 ms**, root `shared read=23993`, met landelijke EVSE Seq Scan; P2e losse uitvoering: **71,316 ms**, root `shared read=0`, uitsluitend lokale indexlookups. De queryplannen tonen een **reële structurele I/O-toegangsverbetering**. Het verschil in gemeten runtime mag **niet** zonder meer als blijvende productiewinst worden opgevat, omdat P2e geheel uit gedeelde buffers werd bediend en P2d veel pagina's moest lezen. Ook was de P2e-executie voorafgegaan door twee inhoudelijke pariteitqueries in dezelfde transactie, die buffers konden voorverwarmen.

**Besluit:** P2e heeft de bedoelde kleine-viewport-optimalisatiefunctioneel bewezen, inclusief exacte locatie-ID- én **volledige aggregaatpariteit**. Geen definitieve landelijke strategie: voor grote/nationale selecties kan 1 lookup per locatie juist duurder zijn dan een tabelscan. De volgende maatregel is daarom **oplopende read-only schaal-/belastingvergelijking** op regio en landelijke volledige-cel-overviews; geen extra Oss-SQL-varianten zonder specifieke aanleiding, en geen productie-API-/Android-implementatie vóór schaalbewijs. PR #114 draft; geen schema-, data-, productiecodewijziging, merge of deploy.
