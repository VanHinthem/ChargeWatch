# ChargeWatch #113 — P2l onafhankelijke landelijke CCS2/DC-pariteit

**3 november 2026 — generator toegevoegd op ontwerpbranch, nog niet lokaal gecompileerd of op NAS uitgevoerd.**

## Context

P2i ongefilterd nationaal (gridLevel8, Nederlandgerichte bbox 3.00,50.60,7.40,53.80): **986,275 ms**, circa 82k locaties, 13 niet-lege cellen. P2j onafhankelijke locatiedekking: **81.945/81.945**, 13/13 niet-lege cellen, 0 verschillen. P2k landelijke CCS2/DC originele connector-first `MapFilter`-query: **265,966 ms**, **1.608 geselecteerde locaties**, **11 niet-lege gridcellen**. P2k `EXPLAIN ANALYZE` kon nog geen exacte nationale filter- of statusrasterpariteit bewijzen.

## P2l: twee *onafhankelijke* selectieroutes binnen één snapshot

CLI `--emit-overview-national-filter-parity` gebruikt dezelfde landelijk uitgelijnde gridLevel8-bounds en dezelfde CCS2/DC-fixture, in één `REPEATABLE READ READ ONLY`-statement met 5s timeout:

1. **Oorspronkelijke selectie en overview** rechtstreeks uit `OverviewNationalBaselineExporter.Create("ccs2_dc")`. Die bouwt de productie-`MapFilter.LocationPredicate` uit de echte gelinkte API-code; alleen `EXPLAIN` wordt weggelaten. Oorspronkelijke `selected`-CTE wordt tevens als zelfstandige `id,cx,cy`-referentie geëxtraheerd (vaste, gevalideerde CTE-marker).
2. **Onafhankelijke technische filterreferentie:** connector-first `connector JOIN evse`, daarna `DISTINCT e.location_id`. Voor deze **ene CCS2/DC-fixture** is het technisch criterium expliciet `upper(coalesce(c.standard,''))='IEC_62196_T2_COMBO'` en `upper(coalesce(c.power_type,''))='DC'`, met actieve connector/EVSE en EVSE-status niet `REMOVED/PLANNED`. Vervolgens alleen de passende locaties via `charging_location_pkey` aan dezelfde geografische grid8-dekking toetsen.
3. **Onafhankelijke availability-aggregatie:** per gematchte locatie `LEFT JOIN LATERAL` EVSE-indexlookup (ongeacht connectorstandaard) met **exact dezelfde bestaande COUNT FILTER-voorwaarden** uit de oorspronkelijke benchmark voor total/free/unclassified. Vervolgens `location_count`, `locations_with_free`, `locations_with_no_free`, `locations_unknown` per `cx,cy`.
4. **Dubbelzijdige `EXCEPT`-vergelijkingen:** (A) alle `(id,cx,cy)`-rijen en (B) de **volledige zes-koloms rasteroutput**, dus ook groen/rood/onbekend. Controleer tevens locatieaantallen en celsommen binnen hetzelfde snapshot.

**PASS:** `original_locations=reference_locations=original_grid_locations=reference_grid_locations`, `original_cells=reference_cells`, `differing_id_cell_rows=0`, `differing_grid_rows=0`, `parity_matches=t`. De aantallen hoeven niet meer precies P2k's 1.608 te zijn als NDW is bijgewerkt.

De referentie is bewust **connector-first**, niet een geforceerde geo-first scan over ~82k locaties. Daardoor veroorzaakt de controle geen landelijke 82k× EVSE-/connector-indexreeks. Dat voorkomt een al in P2h als duur aangetoonde plannerroute.

## Windows: genereren en uploaden

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$lines = dotnet run --no-build --project $project -- --emit-overview-national-filter-parity
if ($LASTEXITCODE -ne 0) { throw 'SQL-generatie mislukt; stop.' }
$sqlText = $lines -join "`n"
if (-not $sqlText.Contains('P2l national CCS2/DC connector-first independent') -or
    -not $sqlText.Contains('ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)') -or
    -not $sqlText.Contains('parity_matches') -or
    -not $sqlText.Contains('differing_grid_rows') -or
    -not $sqlText.Contains('READ ONLY') -or
    -not $sqlText.Contains("statement_timeout = '5s'") -or
    $sqlText.Contains('EXPLAIN (ANALYZE, BUFFERS, SETTINGS)')) {
    throw 'Onverwachte P2l SQL; stop.'
}

$sqlText | Set-Content -Path .\p2l-national-filter-parity.sql -Encoding utf8
scp .\p2l-national-filter-parity.sql hinthem@192.168.2.48:~/p2l-national-filter-parity.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt; stop.' }
```

## NAS: één read-only parity-run

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2l-national-filter-parity.sql \
  | tee ~/p2l-national-filter-parity-result.txt
```

Stuur `p2l-national-filter-parity-result.txt` terug. Bij een timeout of andere fout **niet zelf de time-out verhogen**: we analyseren eerst de oorzaak. Geen landelijke geo-first P2e-variant uitvoeren.

## Grenzen

- De vergelijking bewijst **de CCS2/DC-fixture**, niet alle Q4-combinaties (minFree/power/operator).
- De status-aggregatie volgt exact de **bestaande benchmarkclassificatie**, geen nieuw NDW-/Q5-contract.
- De bbox is geen echte Nederlandse grenspolygoon. Lege coverage-cellen, overlappende viewportaanvragen en werkelijke API-/Android-response zijn nog niet gevalideerd.
- Resultaat nog niet op NAS bewezen; technische generator nog niet lokaal gebouwd. PR #114 blijft draft; **geen merge, deploy, API/Android-wijziging of database/schema/indexmutatie**.


## Gemeten NAS-resultaat — 3 november 2026: PASS

Gebruiker leverde onderstaande volledige resultaatrij van de P2l read-only landelijke CCS2/DC-pariteitsquery aan:

```text
original_locations         1608
reference_locations        1608
differing_id_cell_rows        0
original_cells              11
reference_cells             11
original_grid_locations   1608
reference_grid_locations  1608
differing_grid_rows           0
parity_matches               t
```

**P2l exact geslaagd:** de productie-`MapFilter`-gebaseerde oorspronkelijke selectie en de onafhankelijke connector-first CCS2/DC-referentie leveren hetzelfde volledige `(id,cx,cy)`-resultaat zonder afwijkende rijen. De complete zeskolomsrasteruitvoer — inclusief `location_count`, `locations_with_free`, `locations_with_no_free`, `locations_unknown` — is per cel gelijk, **11/11 cellen** en **1.608/1.608 locaties**, `differing_grid_rows=0`. Dit betreft een vergelijking op hetzelfde database-snapshot en de nationale gridLevel8-omsluitende bbox.

In P2k was de originele landelijke CCS2/DC-query al uitgevoerd in **265,966 ms**, zonder 999-afkapping. De combinatie P2k + P2l bevestigt daarmee voor *deze fixture* zowel een geslaagde performance-run als onafhankelijke exacte selectie- en aggregatiepariteit. Dat is **geen** garantie voor alle andere filtercombinaties of productie-API-latentie.

**Volgende contractgate:** andere productie-MapFilter-fixtures (`minFree`, vermogen, operator, combinaties) via dezelfde onafhankelijke methode toetsen; daarna zoom-/coverage-gedrag en responsecontract voorbereiden. Nog geen productiecodewijzigingen, DB-/NDW-migraties, merge of deployment. PR #114 blijft draft.
