# ChargeWatch #113 — P2j onafhankelijke landelijke rasterpopulatie-census

**3 november 2026 — voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd.**

## Aanleiding

P2i's landelijke, ongefilterde **Web Mercator gridLevel8-overview** was succesvol in **986,275 ms** binnen 5s, met **81.934** geselecteerde actieve locaties in een Nederlandgerichte uitgelijnde bbox, en **13** niet-lege rastercellen. **216.786** actieve EVSE's zijn gescand; per-locatie HashAggregate rapporteerde 5 batches/3.376 kB tijdelijke diskruimte. `EXPLAIN ANALYZE` bewijst echter geen output-`location_count`-som of correcte onafhankelijke celtoewijzing.

## Wat P2j controleert

De nieuwe `--emit-overview-national-census`-CLI genereert **één read-only SQL-statement** met twee onafhankelijke routes binnen exact dezelfde PostgreSQL-`REPEATABLE READ`-snapshot:

- `overview`: de **ongewijzigde originele P2i-productie-MapFilter-gebaseerde SQL** (alleen de `EXPLAIN`-wrapper vervalt), inclusief EVSE-aggregatie en vaste grid8-coördinaten;
- `geo`: een **onafhankelijke** actieve-locatiecensus op dezelfde tot hele rastercellen uitgevouwen WGS84-bbox `(3.00,50.60,7.40,53.80)`, met afzonderlijk berekende Web Mercator `cx,cy` per locatie zonder EVSE/connectorjoin;
- `counted`: per rastercel het aantal onafhankelijke locatie-ID's;
- `differences`: volledige tweerichtings-`EXCEPT`-vergelijking van `cx, cy, location_count` tussen `counted` en `overview`.

Er volgen **negen outputkolommen**:

| Kolom | Betekenis |
|---|---|
| `geography_locations` | Independente geselecteerde locatierijen |
| `geography_distinct_ids` | Unieke locatie-ID's |
| `census_locations` | Som van alle onafhankelijke rasterceltellingen |
| `overview_locations` | Som van location_count uit complete P2i-overview |
| `census_nonempty_cells` | Niet-lege cellen in onafhankelijk raster |
| `overview_nonempty_cells` | Niet-lege cellen uit P2i-overview |
| `differing_cell_counts` | Aantal afwijkende (cel-ID, locatieaantal)-rijen in twee richtingen |
| `invalid_cell_assignments` | Locaties zonder geldige cx/cy of buiten uitgelijnde bounds |
| `population_matches` | Booleaanse invariant: unieke locaties, identieke totalen, identieke per-cel count en geldige celindex |

**PASS vereist:** `geography_locations = geography_distinct_ids = census_locations = overview_locations`, `census_nonempty_cells = overview_nonempty_cells`, `differing_cell_counts=0`, `invalid_cell_assignments=0`, `population_matches=t`. De aantallen kunnen door NDW-updates afwijken van P2i's eerdere **81.934**, maar moeten **binnen dezelfde P2j-run** overeenkomen.

**Reikwijdte:** deze check bewijst de raster-*locatiepopulatie en celcount*-pariteit, niet exacte onderliggende EVSE-/groen-/rood-/onbekend-pariteit, geen expliciete lege-cellen-dekking, geen pannen-overlap tussen requests en geen geografisch exacte landgrenzen. De gebruikte Nederlandse bbox is slechts een omhullende rechthoek.

## Windows — P2j SQL genereren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$lines = dotnet run --no-build --project $project -- --emit-overview-national-census
if ($LASTEXITCODE -ne 0) { throw 'SQL-generator mislukt; stop.' }
$sqlText = $lines -join "`n"
if (-not $sqlText.Contains('P2j independent grid8 geographical census') -or
    -not $sqlText.Contains('ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)') -or
    -not $sqlText.Contains('population_matches') -or
    -not $sqlText.Contains('READ ONLY') -or
    -not $sqlText.Contains("statement_timeout = '5s'") -or
    $sqlText.Contains('EXPLAIN (ANALYZE, BUFFERS, SETTINGS)')) {
    throw 'Onjuiste P2j SQL gegenereerd; stop.'
}

$sqlText | Set-Content -Path .\p2j-national-census.sql -Encoding utf8
scp .\p2j-national-census.sql hinthem@192.168.2.48:~/p2j-national-census.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt; stop.' }
```

## NAS — één read-only census

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2j-national-census.sql \
  | tee ~/p2j-national-census-result.txt
```

De generator voegt `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `SET LOCAL statement_timeout='5s'`, `SET LOCAL lock_timeout='1s'`, `SET LOCAL jit=off`, `ROLLBACK` en `RESET ROLE` toe. Geen mutaties. Bij timeout of andere fout **niet** automatisch langer laten lopen; lever het foutresultaat aan.

## Besluitvorming na P2j

Bij PASS hebben we voor deze bbox en dit snapshot een **onafhankelijk bewijs van volledige locatieaantallen en celtoewijzing zonder 999-afkapping**. Daarna kan de **apart beschikbare** P2i-fase-2 `--emit-overview-national-ccs2` als read-only landelijke filterbenchmark worden overwogen; dat is **nog geen opdracht om hem nu uit te voeren**. Voor een productieoverzicht blijven echte landelijke coverage, selectie/filterpariteit, cel-/clustercontract, lege-cellen-markering en statusclassificatie afzonderlijke acceptatiecriteria.

PR #114 draft; geen API/Android-/collector-/schema-/databasewijziging, merge of deployment.


## Werkelijke NAS-uitkomst — 3 november 2026 (P2j PASS)

Gebruiker leverde de complete console-uitvoer van `--emit-overview-national-census` aan. `SET ROLE`, read-only transactie, alle SELECT's en `ROLLBACK`/`RESET ROLE` werden zonder fout afgewerkt.

| Kolom | Werkelijk |
|---|---:|
| `geography_locations` | **81.945** |
| `geography_distinct_ids` | **81.945** |
| `census_locations` | **81.945** |
| `overview_locations` | **81.945** |
| `census_nonempty_cells` | **13** |
| `overview_nonempty_cells` | **13** |
| `differing_cell_counts` | **0** |
| `invalid_cell_assignments` | **0** |
| `population_matches` | **t** |

**Besluit:** onafhankelijke nationale rasterpopulatiecontrole voor de vaste, uitgelijnde gridLevel8-bbox **geslaagd**. De `overview` telt exact dezelfde **81.945 unieke actieve/gepositioneerde locaties** als de onafhankelijke geocensus, op exact dezelfde **13 niet-lege (cx,cy)-cellen**, zonder afwijkende celcount of ongeldige celtoewijzing. De oude **999-cap** is niet aanwezig in deze SQL-proef. P2i had 81.934 locaties op een eerder snapshot; verschil van 11 kan door NDW-feedwijzigingen komen en is **geen** pariteitsafwijking binnen P2j.

**Beperkingen:** geen echte landgrenspolygoon (slechts NL-omsluitende bbox), geen bewijs dat iedere lege cel expliciet een coverage-record oplevert, geen onderlinge overlappende viewport-test, geen nationale status-/connectorfilterpariteit en nog geen productie-API-endpoint. Dit is **census/count-pariteit**, niet volledige beschikbaarheidspariteit.

**Volgende fase:** nu kan de afzonderlijke P2i landelijke **CCS2/DC**-variant als één read-only benchmark worden uitgevoerd, vervolgens nationale gefilterde ID-/aggregaatpariteit op hetzelfde snapshot en pas daarna eventueel een hybride performance-/API-plan. De geplande nationale gefilterde baseline wordt niet als snel/geoptimaliseerd aangemerkt zonder nieuwe meting. Geen merge, deploy, schema- of productiewijziging; PR #114 draft.
