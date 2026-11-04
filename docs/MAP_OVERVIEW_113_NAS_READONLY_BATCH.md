# ChargeWatch #113 — fase C: éénmalige NAS read-only SQL-meetbatch

**Status 4 november 2026: NAS-read-only meetbatch uitgevoerd en alle drie scenario's PASS.** Fase C gemerged in `main` (PR #117, commit `9fba66e`). De productie-API-container gebruikt nog de oude release; `CW_ENABLE_MAP_OVERVIEW` staat daar uit. Zonder deployment of tijdelijke testcontainer is een echte fase-C-HTTP-benchmark dus **niet** mogelijk. Deze batch toetst de **exacte productie-SQL en rasterselector** tegen de werkelijke NAS-database zonder een nieuwe container te starten of een service te herstarten.

## Praktijkresultaten op de echte NAS-database — 4 november 2026

De gebruiker heeft de complete uitvoer van één `psql`-batch aangeleverd. Alle drie `EXECUTE`-statements zijn binnen dezelfde `REPEATABLE READ READ ONLY`-transactie geslaagd; de `ROLLBACK` en `RESET ROLE` zijn normaal uitgevoerd. Geen retries, timeout of wijziging aan productiediensten.

| Scenario | Effectief gridlevel | Coverage-cellen | Niet-lege markers | Gerepresenteerde locaties | Groen | Rood | Onbekend | SQL-duur |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| NATIONAL_UNFILTERED_Z6 | 8 | 20 | **13** | **83.408** | 71.564 | 11.834 | 10 | **1.464,780 ms** |
| NATIONAL_CCS2_DC_Z12 | 10 | 224 | **84** | **1.612** | 1.399 | 213 | 0 | **164,856 ms** |
| OSS_UNFILTERED_Z15 | 15 | 420 | **116** | **489** | 441 | 48 | 0 | **13,035 ms** |

Voor alle drie: `status_partition_valid=t`, `marker_budget_valid=t`, de som van de drie statusgroepen is exact de gerapporteerde locatiepopulatie, en alle markeraantallen zijn ≤500.

**Interpretatie:** de landelijke ongefilterde query vertegenwoordigt 83.408 locaties in 13 niet-lege rastermarkers, zonder ruwe `LIMIT 999` en binnen de afgesproken 5s-statement-timeout. De 13 objecten zijn **markers per bezette cel** (niet onafhankelijk vastgesteld als allemaal clusterobjecten). Beschikbaarheidskleuren zijn **locatieclassificaties**: groen betekent minimaal één vrij operationeel EVSE op die locatie, niet dat alle laadpunten vrij zijn. De getallen vergelijken niet één-op-één met oudere NDW-momentopnamen, omdat data tussentijds wijzigt.

**Performancebeoordeling:** SQL-batchgate PASS. De landelijke worst-case uit deze drie losse runs is 1,465 seconde SQL-tijd: dat is relevant bij frequente pan/zoom en gelijktijdige aanvragen, maar vormt op zichzelf geen blokkade voor een **uitgeschakeld** backendcontract in main. Er is nog geen SQL-p95, geen parallelle loadtest, geen HTTP-deserialisatie-/serialisatietijd, geen over-the-wire responstijd en geen gemeten Android-renderlatency. Geen productie-SLA afleiden uit één meting.

**Besluit voor vervolg:** niet opnieuw alle historisch geslaagde Q4-/H2-pariteitsprobes uitvoeren. De volgende gate is een gecontroleerde featureflag-isolatie/HTTP-beoordeling vóór activatie op de NAS en daarna gefaseerde Android-MapLibre-integratie; aparte productie-deploymenttoestemming is nodig. PR #118 bevat alleen de meetgenerator, workflow en documentatie en blijft draft tot expliciet mergeakkoord.

---

## Waarom deze ene batch?

De generator `tools/MapFilterQ4Probe/OverviewPhaseCNasReadOnlyExporter.cs` linkt dezelfde `MapFilter.cs`, `MapOverviewSql.cs`, `MapGrid.cs` en `MapOverviewGridSelector.cs` als de echte API. Hij maakt precies **drie** vooraf vastgelegde, begrensde `PREPARE/EXECUTE`-queries in één `REPEATABLE READ READ ONLY`-transactie:

| Scenario | Bbox | Zoom | Doel |
|---|---|---|---|
| NATIONAL_UNFILTERED_Z6 | 3.00,50.60,7.40,53.80 | 6 | Volledige Nederlandse omsluitende rechthoek: locatieaantal, budget, statusringen |
| NATIONAL_CCS2_DC_Z12 | Zelfde | 12, CCS2+DC | Landelijke filterquery op conservatief geselecteerd raster |
| OSS_UNFILTERED_Z15 | 5.40,51.70,5.62,51.83 | 15 | Meer detail, exacte losse markers/clusters en regionale performance |

Per scenario één echte uitvoering, geen N× herhaalde scans. De resultaten zijn: `marker_count`, `represented_locations`, groene/rode/onbekende locatieaantallen, `status_partition_valid`, `marker_budget_valid`, en door `psql \timing` de **SQL-uitvoeringstijd**. De drie queries delen één database-snapshot; de feed kan vóór/na de batch verder importeren.

**Veiligheidsgrenzen:** `SET ROLE chargewatch_read`, `BEGIN ... READ ONLY`, `statement_timeout=5s` per statement, `lock_timeout=1s`, `jit=off`; script bevat alleen SELECT/PREPARE/EXECUTE/DEALLOCATE, eindigt met ROLLBACK/RESET ROLE. **Geen** schemawijziging, DML, API-restart, Docker-run/up, DB-poortpublicatie, SSH-forwarding of deployment. Dit is een beperkte SQL-meting, **geen** betrouwbare HTTP-p50/p95 of concurrencymeting. Een time-out is een meetresultaat: geen automatische retries of hogere limieten.

## Eén gebundelde uitvoering (na groen CI-resultaat van de generator-PR)

**Windows PowerShell, in de bestaande lokale repository:**

```powershell
cd C:\GITHUB\ChargeWatch
git fetch origin
git switch test/map-overview-113-nas-readonly-batch
git pull --ff-only
$path = Join-Path $env:TEMP 'chargewatch-p2n-phasec.sql'
$sqlText = (& dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-overview-phasec-nas-batch) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'SQL-export mislukt, niet uitvoeren.' }
[System.IO.File]::WriteAllText($path, $sqlText + "`n", [System.Text.UTF8Encoding]::new($false))
if ((Select-String -Path $path -Pattern '^EXECUTE cw_p2n_').Count -ne 3) { throw 'Onverwacht aantal SQL-queries.' }
scp $path hinthem@HINNAS:/tmp/chargewatch-p2n-phasec.sql
```

**NAS-shell, waar de bestaande Compose-service bereikbaar is:**

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose ps db
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch \
  < /tmp/chargewatch-p2n-phasec.sql | tee /tmp/chargewatch-p2n-phasec-result.txt
rm /tmp/chargewatch-p2n-phasec.sql
```

Plak daarna de drie scenarioresultaten en `Time:`-regels hier in de chat. Het outputbestand bevat alleen geaggregeerde aantallen/querytijden, geen secrets of ruwe laadlocatiegegevens. De owner-verbinding wordt binnen de sessie met `SET ROLE chargewatch_read` teruggezet naar alleen-lezen; er is geen extra credential nodig. Controleer het gegenereerde script als de branch afwijkt of CI niet groen is.

**Let op:** de pipe naar `tee` in Bash rapporteert standaard de exitstatus van `tee` in plaats van `psql`. Gebruik bij een interactieve Bash-shell `set -o pipefail` vooraf of verwijder `| tee ...` als de NAS-shell die optie niet ondersteunt. Stop bij iedere SQL-fout, geen herhaalbatch starten.

## Interpretatie, daarna Android

Voor elke regel verwachten we `marker_budget_valid=t`, `status_partition_valid=t`, `marker_count<=500` en `represented_locations = groen+rood+onbekend`. Landelijk `represented_locations` is geen officiële statistiek over landsgrenzen: de bbox is een rechthoek en bevat mogelijk locaties buiten NL. Vergelijk niet hardcoded met oudere NDW-aantallen; nieuwe imports kunnen inmiddels meer/minder locaties bevatten.

Als dit binnen de 5-secondenbewaker draait, beoordelen we alleen de zwaarste SQL-plannen verder als de gemeten tijden daar aanleiding toe geven. Voor de volgende Android-fase ontwerpen we een rollout waarbij de bestaande viewport behouden blijft en de nieuwe endpointfunctie pas na afzonderlijke productieactivering gebruikt wordt.
