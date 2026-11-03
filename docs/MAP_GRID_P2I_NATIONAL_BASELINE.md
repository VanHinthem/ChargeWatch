# ChargeWatch #113 — P2i landelijke raster-baseline (gefaseerd)

**3 november 2026 — benchmarkcode voorbereid, nog niet gebouwd of op NAS uitgevoerd.**

## Waarom nu deze test?

P2g bevestigde exacte regio-pariteit (420/420 IDs, 12/12 cellen, nul afwijkingen). P2h liet zien dat bij die grotere CCS2/DC-regio de **oorspronkelijke connector-first route** gemiddeld 186 ms draaide, tegenover 768 ms voor de geforceerd locatiegerichte variant. Er is dus **geen betrouwbare universele queryroute op grond van zoom alleen**. De kernvraag van #113 is of een volledige landelijke kaart zonder de oude 999-locatielimiet als **compacte, volledige Web Mercator-rasteraggregatie** kan worden berekend.

Deze test gebruikt daarom uitsluitend de bestaande oorspronkelijke overview-queryvorm via `OverviewPerformanceExporter.CreateScenario`, met de **gelinkte productie-`MapFilter`**. De enige wijzigingen in de SQL zijn de vaste bbox en raster-celmaat.

### Geografische afbakening

- **Nederlandgerichte omsluitende rechthoek**: `ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)`, WGS84. Dit is **geen landsgrenzenpolygon**: punten in aangrenzende gebieden worden niet op nationaliteit uitgesloten, en dekking is pas bewezen na een onafhankelijk tel-/eigendomsonderzoek.
- **Web Mercator gridLevel8**: `cell_m = 40075016.68557849 / 256`, vaste wereldrasteroorsprong, volledige gevraagde rastercellen via de bestaande bbox-expansie.
- Uitvoer: alleen **niet-lege** rastercellen; dit is niet gelijk aan een expliciete dekking voor lege cellen of een API-`coverage`-contract.
- **Geen `LIMIT 999` op ruwe locaties in deze benchmark-SELECT.** Dit moet nog met een expliciete locatiesom en grondwaarheidcontrole bewezen worden voor een product-API-contract.

### Twee strikt afzonderlijke runfasen

**Fase 1 (nu):** `--emit-overview-national-unfiltered` exporteert **één** `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` zonder `MapFilter`-criteria. Deze duurste, meest relevante landelijke baseline eerst inspecteren. **Voer fase 2 nog niet uit** voordat de resultaten zijn beoordeeld.

**Fase 2 (later, uitsluitend na analyse):** `--emit-overview-national-ccs2` exporteert de originele `ccs2_dc`-variant op identieke bbox/gridLevel. Beide exports zijn van elkaar gescheiden; zo vermijden we onnodige gelijktijdige NAS-belasting.

Elke export houdt `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK` en `RESET ROLE`. Timeout is een grensresultaat; niet blind verhogen.

## Windows — uitsluitend fase 1 exporteren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$lines = dotnet run --no-build --project $project -- --emit-overview-national-unfiltered
if ($LASTEXITCODE -ne 0) { throw 'SQL-generator mislukt; stop.' }
$sqlText = $lines -join "`n"
if ([regex]::Matches($sqlText, 'EXPLAIN \(ANALYZE, BUFFERS, SETTINGS\)').Count -ne 1 -or
    -not $sqlText.Contains('P2i Netherlands-oriented bbox, gridLevel8, fixture=unfiltered') -or
    -not $sqlText.Contains('ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)') -or
    -not $sqlText.Contains('40075016.68557849::double precision / 256.0') -or
    -not $sqlText.Contains('READ ONLY') -or
    -not $sqlText.Contains("statement_timeout = '5s'")) {
    throw 'Onjuiste P2i SQL; stop.'
}

$sqlText | Set-Content -Path .\p2i-national-unfiltered.sql -Encoding utf8
scp .\p2i-national-unfiltered.sql hinthem@192.168.2.48:~/p2i-national-unfiltered.sql
if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt; stop.' }
```

## NAS — fase 1 (één read-only EXPLAIN)

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2i-national-unfiltered.sql \
  | tee ~/p2i-national-unfiltered-result.txt
```

**Stop na fase 1** en stuur `p2i-national-unfiltered-result.txt` terug, ook bij een timeout. Geen landelijke gefilterde run, geen nieuwe indexen of grotere statement-timeout op eigen initiatief.

## Beoordelingskader

Analyseer `Execution Time`, `Planning Time`, root `shared hit/read/written`, `temp read/written`, landelijke geselecteerde-locatie-aantallen, `evse`-joinstrategie en rijen, `GroupAggregate` output-celaantal, sort/hash-spills en waarschuwingen. De `EXPLAIN`-uitvoer bevat géén daadwerkelijke celwaarden of som van locaties: **een teruggegeven aantal rastercellen bewijst niet dat >999 locaties in die cellen zijn meegeteld**. De inhoudelijke dekking en eventuele externe rechthoek versus landsgrenzen worden daarom in een **afzonderlijke exacte read-only tel-/pariteitsproef** onderzocht.

Als fase 1 binnen 5 seconden voltooit, bepalen we of een gelijkwaardige ongefilterde resultsetpariteit of CCS2/DC-benchmark nuttiger is als volgende stap. De vraag naar hybride dispatch op basis van schaal en filterselectiviteit blijft apart; **geen zoomdrempel of API-ontwerpbesluit op deze ene run**.

PR #114 draft. Geen productie-API-/Android-code, DB-migratie/index/schemawijziging, merge of deployment.


## Werkelijke NAS-uitkomst fase 1 — 3 november 2026

Gebruiker leverde `p2i-national-unfiltered-result.txt` met een succesvol afgeronde read-only `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` (geen timeout).

| Observatie | Gemeten |
|---|---:|
| Planning Time | **26,881 ms** |
| Execution Time | **986,275 ms** |
| `selected` geografische locaties | **81.934** |
| Uitgevoerde landelijke EVSE Seq Scan, actieve EVSE-rijen | **216.786** |
| Joinrijen na selectie × EVSE | **206.207** |
| `GroupAggregate` rastercellen | **13 niet-lege cellen** |
| Root shared buffers | `hit=3794 read=50661 dirtied=5326 written=6748` |
| Root temporary buffers | `temp read=248 written=941` |
| Per-location HashAggregate | `Batches: 5; Memory Usage: 7737kB; Disk Usage: 3376kB` |
| SELECTED CTE opslag | `Storage: Disk; Maximum Storage: 4096kB` |

### Interpretatie

- De oorspronkelijke landelijke ongefilterde rasterquery **draait binnen de 5s grens** en neemt **81.934 ruwe locaties** mee in de geselecteerde CTE. De kaartresultaatvorm is slechts **13 niet-lege cellen** op Web Mercator gridLevel8. Er is **geen LIMIT 999** in deze SQL-route. Dit ondersteunt de haalbaarheid van compacte landelijke aggregatie; een echte API response-/coverage-contractimplementatie is er nog niet.
- De `Hash Right Join` gebruikt een volledige `Seq Scan on evse` met **216.786 actieve EVSE-rijen**. Bij dit grote selectiegebied kan een brede scan juist rationeel zijn. De `HashAggregate` voor 81.934 locaties valt uiteen in 5 batches, met beperkte tijdelijke schijf-I/O (3.376 kB `Disk Usage`).
- Deze `EXPLAIN` retourneert **geen celwaarden, locatie-totalensom, exact cel-lidmaatschap of onafhankelijke telling**. We mogen dus niet concluderen dat precies 81.934 actieve locaties correct en zonder doublures in de 13 cellen zijn opgeteld op grond van de EXPLAIN alleen. De bbox is een Nederlandgerichte omsluitende rechthoek, geen landspolygon.
- De 13 cellen zijn niet-lege resultaatcellen, **niet** een gegarandeerd expliciete `coverage`-lijst met lege cellen.
- **Geen landelijke CCS2/DC-benchmark uitvoeren voordat een onafhankelijke volledige rasterlocatie-census/pariteitcontrole is beoordeeld.** Filterafwijkingen, ontbrekende eiland-/grensgebieden en stabiliteit bij pan blijven afzonderlijke tests.

**Vervolg P2j:** een eenvoudige, enkelvoudige read-only census van alle actieve, gepositioneerde locaties binnen de uitgelijnde grid8-bounds, met onafhankelijk berekende `COUNT(*)`-grondwaarheid, een rastergroepering met `SUM(location_count)` en `COUNT(DISTINCT id)` plus controle op precies één cell-toewijzing. Eventueel controleer de `country_code`-verdeling voor niet-NL-locaties in de rechthoek, maar verwar deze broncode niet met een geometrische landsgrens. Gebruik dezelfde vaste complete-cell-grenzen; geen DB-schemamutatie. Daarna pas gefilterde landelijke performance.
