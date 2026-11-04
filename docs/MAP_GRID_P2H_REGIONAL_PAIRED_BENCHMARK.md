# ChargeWatch #113 — P2h gepaarde regionale performancebenchmark

**3 november 2026 — voorbereid, NIET lokaal gecompileerd of op NAS uitgevoerd.**

## Aanleiding

P2g regionale correctness gates zijn beide geslaagd binnen het zoom10-gebied `(5.20,51.60,5.90,52.10)`: **exacte ID-pariteit 420=420, 0 afwijkende ID's** en **complete rasterpariteit 12=12 cellen, 420=420 locaties, 0 afwijkende cellen**. P2f testte de geoptimaliseerde route één keer: **866,215 ms** met 22.827 geografische kandidaten, 420 geselecteerde locaties en 86.722 shared reads. Voor een strategie-/API-besluit ontbreekt de oorspronkelijke route op **hetzelfde gebied** en een korte afwisselende reeks.

## Exacte test

`--emit-overview-region-paired` genereert **vier onafhankelijk read-only `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`-statements**, één per `chargewatch_read`-transactie:

| Run | Variant | Herkomst |
|---|---|---|
| 1 | `original` | `OverviewPerformanceExporter.CreateScenario("ccs2_dc")`, uitsluitend Oss-bbox en grid-celmaat vervangen |
| 2 | `indexed` | `OverviewGeoFirstExporter.CreateMediumRegionCcs2Dc()` (precies P2f) |
| 3 | `indexed` | exact dezelfde SQL als run 2 |
| 4 | `original` | exact dezelfde SQL als run 1 |

Alle runs gebruiken **de productie-`MapFilter.cs`** voor `connectorType=ccs2&mode=dc`, dezelfde regionale bbox, dezelfde Web Mercator gridLevel10-celgeometrie, dezelfde bestaande benchmark-COUNT-statusfuncties en de bestaande ruimtelijke `ST_Intersects`-voorwaarden. Beide routes aggregeren na filtering dezelfde algemene actieve EVSE's per locatie, ongeacht technisch filter. De enige variatie is de queryopbouw. Er zijn geen nieuwe filter- of statusregels.

### Veiligheidsgrenzen

- Elke variant: `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `SET LOCAL statement_timeout='5s'`, `SET LOCAL lock_timeout='1s'`, `SET LOCAL jit=off`, `ROLLBACK`, `RESET ROLE`.
- De P2g-pariteit is reeds geslaagd; dus **geen extra warm-up/pariteitsquery vóór de vier EXPLAIN's**. Dit voorkomt asymmetrische opwarming door de correctness-queries.
- Deze vier runs zijn exploratief, **geen p95**, geen garantie van gelijke diskcache, geen landelijke schaalclaim. Een timeout is een nuttige grensmeting: niet blind verhogen.
- **Geen nationale uitvoering** en geen DB/schema/index/NDW-/API-/Android-wijzigingen.

## Windows: genereren en controleren

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-region-paired
if ($LASTEXITCODE -ne 0) { throw 'SQL-generator mislukt; stop.' }

# Native command output is een array van tekstregels. Controleer als één string.
$sqlText = $sql -join "`n"
$explainCount = [regex]::Matches($sqlText, 'EXPLAIN \(ANALYZE, BUFFERS, SETTINGS\)').Count
$runCount = [regex]::Matches($sqlText, '(?m)^\\echo P2h region run=[1-4] variant=(original|indexed)
scp .\p2h-region-paired.sql hinthem@192.168.2.48:~/p2h-region-paired.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt; stop.' }
```

## NAS: één runreeks

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2h-region-paired.sql \
  | tee ~/p2h-region-paired-result.txt
```

Bij timeout/PSQL-fout geen extra runs of nationale benchmark starten. Bewaar de volledige uitvoer met `P2h region run=...`-markeringen en stuur `p2h-region-paired-result.txt` terug.

## Beoordeling

Per run: `Execution Time`, `Planning Time`, root `Buffers shared hit/read/written`, `geo_locations` en `selected` aantallen, aantal outputcellen, `Parallel Seq Scan on connector` of `Seq Scan on evse`, aantallen lokale EVSE-/connector-indexzoekacties, eventuele `temp read/written`. Beslissen op queryplan + herhaalbaarheid en I/O-kosten, **niet** uitsluitend de snelste run. De nationale gebruikslast (lagere gridlevels; grotere geografische kandidatenset) blijft een afzonderlijk nog niet gevalideerd scenario.

PR #114 draft; geen merge of deployment.
).Count
if ($explainCount -ne 4 -or $runCount -ne 4 -or
    -not $sqlText.Contains('P2h region run=1 variant=original') -or
    -not $sqlText.Contains('P2h region run=4 variant=original') -or
    -not $sqlText.Contains('ST_MakeEnvelope(5.20,51.60,5.90,52.10,4326)') -or
    -not $sqlText.Contains('40075016.68557849::double precision / 1024.0') -or
    -not $sqlText.Contains('40075016.68557849::double precision/1024.0')) {
    throw "P2h SQL-check mislukt: EXPLAIN=$explainCount; runs=$runCount. Stop."
}
$sqlText | Set-Content -Path .\p2h-region-paired.sql -Encoding utf8
scp .\p2h-region-paired.sql hinthem@192.168.2.48:~/p2h-region-paired.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP upload mislukt; stop.' }
```

## NAS: één runreeks

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2h-region-paired.sql \
  | tee ~/p2h-region-paired-result.txt
```

Bij timeout/PSQL-fout geen extra runs of nationale benchmark starten. Bewaar de volledige uitvoer met `P2h region run=...`-markeringen en stuur `p2h-region-paired-result.txt` terug.

## Beoordeling

Per run: `Execution Time`, `Planning Time`, root `Buffers shared hit/read/written`, `geo_locations` en `selected` aantallen, aantal outputcellen, `Parallel Seq Scan on connector` of `Seq Scan on evse`, aantallen lokale EVSE-/connector-indexzoekacties, eventuele `temp read/written`. Beslissen op queryplan + herhaalbaarheid en I/O-kosten, **niet** uitsluitend de snelste run. De nationale gebruikslast (lagere gridlevels; grotere geografische kandidatenset) blijft een afzonderlijk nog niet gevalideerd scenario.

PR #114 draft; geen merge of deployment.


## Correctie PowerShell-validator — 3 november 2026

De eerste Windows-run compileerde en genereerde SQL, maar de check `($sql -split 'EXPLAIN ...').Count -ne 5` faalde. **Oorzaak:** `$sql` is bij PowerShell een array van uitvoerregels; `-split` op een array telt niet uitsluitend vier `EXPLAIN`-statements. De documentatie is gecorrigeerd naar `$sqlText = $sql -join "`n"` plus `[regex]::Matches(...).Count -eq 4` en vier expliciete `\\echo`-runlabels. De fout is **geen aangetoonde fout in de gegenereerde SQL**.

**Een SQL-bestand dat al met `Set-Content` is gemaakt na de mislukte `if` mag pas naar NAS worden gestuurd als bovenstaande nieuwe check slaagt.** Genereer en valideer opnieuw, schrijf daarna het bestand pas weg. De code of de daadwerkelijke SQL-benchmark is hierdoor niet gewijzigd. Er is nog **geen** succesvolle P2h NAS-uitvoering bevestigd.


## P2h daadwerkelijke NAS-uitkomst — 3 november 2026

Gebruiker leverde het volledige `p2h-region-paired-result.txt` aan, met vier succesvol afgeronde read-only `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`-runs onder de vastgelegde regionale bbox/zoom10/CCS2+DC-fixture. **Geen SQL-timeouts of fouten**.

| Run | Variant | Planning (ms) | Execution (ms) | Root shared hit | Root shared read | Root shared written | Selecties | Niet-lege cellen |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | original | 31,764 | **222,733** | 28.289 | 31.594 | 2.394 | 420 | 12 |
| 2 | indexed | 3,660 | **857,348** | 274.538 | 83.549 | 8.029 | 420 | 12 |
| 3 | indexed | 3,514 | **678,463** | 278.807 | 79.226 | 147 | 420 | 12 |
| 4 | original | 3,918 | **149,309** | 34.762 | 24.966 | niet gemeld | 420 | 12 |

Gemiddelden van **slechts 2 runs**: original **186,021 ms**, indexed **767,906 ms** (indexed circa **4,13×** trager). De eerste original heeft een relatief hoge planningtijd (31,764 ms), maar Execution Time sluit planning uit. De I/O-verschillen zijn groot en alle runs kampen met andere cache-/schijfbelasting; een betrouwbaar p95/p99-SLA- of latentiestatement is hiermee niet aangetoond. De gemeten planstructuur ondersteunt echter de richting van het verschil.

### Belangrijkste technische verklaring

- **Original:** `Parallel Seq Scan on connector` voor de technische CCS2/DC-match, ~6.473 matchende connectoren / 6.467 onderscheiden `evse_id`'s / 1.637 locaties vóór regio-geofilter. `charging_location_pkey` wordt 1.637× benaderd. De finale EVSE-aggregatie gebruikt `idx_evse_active_location` **420×**, niet een landelijke EVSE-scan. De oorspronkelijke SQL blijft **op de regio sneller**, ondanks de nationale connector-Seq Scan.
- **Indexed:** eerst GiST `geo_locations` **22.839** locaties; per kandidaat `idx_evse_active_location` **22.839×** en `idx_connector_active_evse` **54.031×**, om uiteindelijk dezelfde **420** locaties te selecteren. Daarna 420 indexlookups voor 2.530 EVSE-rijen. Er is geen nationale connector-/EVSE-Seq Scan, maar deze vele willekeurige indexprobes verhogen de I/O en latentie.
- **P2g full correctness gate:** op deze bbox was exacte locatie-ID-pariteit **420/420, diff=0** en volledige zes-koloms rasteraggregaatpariteit **12/12 cellen, diff=0**. De P2h-runs zelf geven slechts aantallen/cellen, geen nieuwe EXCEPT-controle; P2g is de correctnessbron. `geo_locations` telde bij P2f 22.827, bij P2h 22.839 (gewijzigde NDW-snapshot), terwijl de matches gelijk bleven.
- Geen gemelde temp-spill; geen database- of productiecodewijzigingen.

### Architectuurconclusie

**Niet P2e als enige server-side querystrategie invoeren.** Voor CCS2/DC in deze regionale viewport is de oorspronkelijke connector-first route in beide gepaarde runs substantieel beter. Bij kleine Oss-viewports heeft P2e een correct en geschikt lokaal indexpad bewezen, maar de losse warme P2e-run van 71,316 ms tegenover P2c-original 134–201 ms is **onvoldoende** om de crossover-/hybridedrempel te bepalen. Er is nog géén bewijs voor een universele zoomgrens; zoom en bbox zijn een proxy voor het aantal geografische kandidaatlocaties, maar filterselectiviteit en plannerstatistieken zijn ook belangrijk.

**Voorgestelde volgende fase:** ontwerp een **selectiviteits-/omvangbewuste querykeuze** die de bestaande originele en P2e-route beide behoudt (geen nieuwe filtersemantiek) en toets daarnaast de nationale zoom-/dekkingseisen. Concrete omschakeling of landsdekkende duur moet nog worden vastgesteld; een nationaal verzoek niet blind via P2e forceren. Voorzichtig omgaan met lege-cellen-/coverage-semantiek en de 999-limitatie uit #113. Geen merge/deploy/API-/Android-implementatie zonder expliciete toestemming.

**PR #114 blijft draft.**
