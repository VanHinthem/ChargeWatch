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


## Werkelijke NAS-resultaten — 3 november 2026, P2b eerste uitvoering

Gebruiker leverde de complete read-only psql-uitvoer aan voor beide fixtures, inclusief `EXCEPT`-ID-pariteit en `EXPLAIN (ANALYZE, BUFFERS)`. **Alle onderstaande waarden zijn gemeten, niet geschat.**

| Scenario | Oorspronkelijke locaties | Geography-first locaties | Verschillende ID's | IDs gelijk | P2a origineel (ms) | P2b geography-first (ms) |
|---|---:|---:|---:|---|---:|---:|
| `ccs2_dc` | 34 | 34 | 0 | **t** | 160,011 | **187,509** |
| `ccs2_dc_minfree1` | 30 | 30 | 0 | **t** | 137,799 | **41,749** |

Beide P2b-resultaten eindigen in **vier rastercellen**.

### Plan `ccs2_dc`: landelijke scan blijft bestaan

- `geo_locations AS MATERIALIZED`: **2.101** geografische locaties; active spatial GiST-index `ix_charging_location_active_position` gebruikt.
- `selected`: `Hash Semi Join` met **34** geselecteerde locaties.
- Ondanks geografische materialisatie blijft er een **Parallel Seq Scan van alle connectoren**: **6.473** passende connectorrecords, `connector` scan `shared hit=3387 read=20453`, daarna **6.467 EVSE-PK-indexzoekacties**.
- Daarna `idx_evse_active_location` voor **34** locaties bij availability-join.
- Root buffers: `shared hit=27029 read=25939 dirtied=409 written=3821`.
- Planning Time **3,371 ms**, Execution Time **187,509 ms**. Vergeleken met P2a 160,011 ms **trager**, maar cache/collectorbelasting varieerde.

### Plan `ccs2_dc_minfree1`: lokale indexstrategie

- `geo_locations AS MATERIALIZED`: **2.101** geografische locaties, GiST-index gebruikt.
- PostgreSQL kiest `Nested Loop Semi Join` en per-locatie `minFree`-subplan: **2.101** `idx_evse_active_location` searches en **3.578** `idx_connector_active_evse` searches, waarna **30** locaties resteren. Daarna aanvullende technische EXISTS-controle: 30 EVSE-indexsearches en 54 connector-indexsearches; finale availability-join voor 30 geselecteerde locaties (235 rows).
- **Geen landelijke connector Seq Scan** in deze run.
- Root buffers: `shared hit=28962 read=31 written=1`; dit is een **overwegend warme-cache-run** en niet direct causaal vergelijkbaar met P2a (23.531 reads).
- Planning Time **3,047 ms**, Execution Time **41,749 ms**. In dit sample ~3,3× sneller dan P2a, maar **niet bewezen structureel**.

### Besluit na eerste run

**Functioneel ID-pariteit geslaagd in beide gemeten scenario's.** Een gematerialiseerde geografische CTE forceert **niet** altijd een location-first join: de optimizer maakt de selectie op basis van predicaatkosten en cardinaliteit. Bij `ccs2_dc` blijft de landelijke connectorscan bestaan; bij `ccs2_dc_minfree1` verdwijnt die. Geen generieke 'geography-first is sneller'-claim en geen API-/SQL-productiewijziging op basis van deze losse resultaten.

**Volgende stap:** vergelijk P2a versus P2b **onder dezelfde warmere cachecondities en afwisselende volgorde** voor beide gefilterde gevallen, of onderzoek met `EXPLAIN` een **gerichte gecorreleerde EVSE-/connector-indexjoin vanaf de geo-set** als later ook `ccs2_dc` aantoonbaar achterblijft. Filtersemantiek blijft altijd door de gelinkte productie-`MapFilter` gegenereerd. Nationale performance en filters anders dan deze twee zijn nog niet getest. PR #114 blijft draft, geen mutaties/merge/deploy.
