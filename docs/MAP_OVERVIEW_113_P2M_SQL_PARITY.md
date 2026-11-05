# ChargeWatch #113 — P2m: fase-B SQL-integratiepariteit tegen P2l

**4 november 2026 — generator gecommit, nog niet lokaal gebouwd of op NAS uitgevoerd.**

## Waarom deze test?

De gebruiker bevestigde succesvolle Windows-selftests op de fase-B-branch:
`ChargeWatch API selftest passed.` en `Map overview phase A selftest passed.`

Die tests voeren de nieuwe `MapOverviewDatabase.ReadAsync`-query **niet** tegen PostgreSQL uit. De eerdere P2l-test bewees het oorspronkelijke nationale CCS2/DC-overviewcontract, niet de nieuw geschreven API-SQL. P2m controleert daarom beide SQL-routes op één **read-only repeatable-read** databasesnapshot.

## Enige SQL-bron in fase B

De fase-B-query is uit `MapOverviewDatabase.ReadAsync` naar `src/ChargeWatch.Api/MapOverviewSql.cs` verplaatst en wordt **door zowel het toekomstige endpoint als de probe** via `MapOverviewSql.Build(MapFilter)` gebruikt. De probe compileert diezelfde productiebron (plus echte `MapFilter.cs`) als gelinkte bron. Daarmee vermijden we een tweede, afwijkende kopie van de nieuwe API-SQL in het testscript. Geen functionele wijziging aan het reeds geschreven endpoint beoogd.

De `OverviewPhaseBParityExporter` reconstrueert de P2l-nationale uitgelijnde **Web Mercator gridLevel8**-coverage, bbox `3.00,50.60,7.40,53.80`, fixture `connectorType=ccs2&mode=dc`, en vult uitsluitend de SQL-parameters van de echte fase-B-query in met de berekende complete celbounds. De **oorspronkelijke referentiequery** is gegenereerd met bestaande `OverviewNationalBaselineExporter`, dezelfde echte `MapFilter`. De vergelijking vindt plaats vóór HTTP/Android.

## Te verwachten resultaatkolommen

| Kolom | Definitie |
|---|---|
| `original_locations`, `phaseb_locations` | Aantal geselecteerde locaties door oorspronkelijke versus nieuwe SQL |
| `differing_id_cells` | Dubbelzijdige EXCEPT van `id,cx,cy` |
| `original_cells`, `phaseb_cells` | Aantal niet-lege rastercellen |
| `phaseb_grid_locations` | Som van nieuwe `location_count` |
| `differing_full_grid_rows` | Dubbelzijdige EXCEPT van alle zes rastervelden (cx/cy/count/groen/rood/onbekend) |
| `invalid_status_partitions` | Rastercellen waarvan groen+rood+onbekend niet het totale locatieaantal is |
| `single_location_cells`, `cluster_cells` | Welke van de rastercellen één versus meerdere laadlocaties bevatten |
| `parity_matches` | Alle ID-, cellen- en somchecks tegelijk geslaagd |

**PASS:** `original_locations=phaseb_locations=phaseb_grid_locations`; `original_cells=phaseb_cells=single_location_cells+cluster_cells`; `differing_id_cells=0`, `differing_full_grid_rows=0`, `invalid_status_partitions=0`, `parity_matches=t`. Aantallen kunnen afwijken van P2l's **1.608** door NDW-updates; de invariant geldt **binnen P2m zelf**.

**Beperking:** dit vergelijkt de *exacte SQL* (met in de probe equivalent ingevulde bounds/filters), niet de daadwerkelijke Npgsql-binding over HTTP, de runtime-C# `MapGrid.Cover` voor iedere zoom, de uiteindelijke DTO-serialisatie of bestaande Android/legacy countQuality-criteria. Die checks blijven apart open.

## Windows — SQL-generatie en upload

```powershell
cd C:\GITHUB\ChargeWatch
git switch feat/map-overview-113-phase-b
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'MapFilter-probe build mislukt; stop.' }

$lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-parity
if ($LASTEXITCODE -ne 0) { throw 'P2m SQL-generator mislukt; stop.' }
$sqlText = $lines -join "`n"

if (-not $sqlText.Contains('P2m phase-B exact SQL parity') -or
    -not $sqlText.Contains('differing_id_cells') -or
    -not $sqlText.Contains('differing_full_grid_rows') -or
    -not $sqlText.Contains('READ ONLY') -or
    -not $sqlText.Contains("statement_timeout = '5s'") -or
    $sqlText.Contains('EXPLAIN (ANALYZE, BUFFERS, SETTINGS)') -or
    [regex]::IsMatch($sqlText, '\$[1-9][0-9]*(?![0-9])')) {
    throw 'Onverwachte P2m SQL; stop.'
}
$sqlText | Set-Content .\p2m-phaseb-parity.sql -Encoding utf8
scp .\p2m-phaseb-parity.sql hinthem@192.168.2.48:~/p2m-phaseb-parity.sql
if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt; stop.' }
```

## NAS — read-only vergelijking

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2m-phaseb-parity.sql \
  | tee ~/p2m-phaseb-parity-result.txt
```

Stuur `p2m-phaseb-parity-result.txt` terug. **Bij een fout of 5s-timeout eerst analyseren, niet opnieuw proberen met verhoogde timeout.**

## Veiligheidsgrenzen

- `SET ROLE chargewatch_read`, `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`, `lock_timeout=1s`, `statement_timeout=5s`, `jit=off`, `ROLLBACK`; één P2m-query.
- Geen writes, migration, index, NDW collector, Android, merge of deploy.
- `CW_ENABLE_MAP_OVERVIEW` blijft standaard uit; deze SQL-probe start **geen HTTP-endpoint**.
- Na P2m PASS: nog een kleine lokale/HTTP-integratiegate en legacy-availability kwaliteitsreview vóór activering. Fase C (coarsening bij 500) blijft apart.


## P2m eerste NAS-uitkomst — 4 november 2026: TIMEOUT, geen pariteitsuitspraak

Gebruiker voerde `p2m-phaseb-parity.sql` uit. Console-uitvoer:

```text
SET
BEGIN
SET
SET
SET
ERROR: canceling statement due to statement timeout
```

**Dit is geen P2m PASS of FAIL op juistheid:** de gecombineerde query is na **5 seconden** afgebroken, zonder inhoudelijke resultaatrij. P2k's **265,966 ms** is gemeten voor de *enkele oorspronkelijke overviewquery*; P2m bevat **vier** zware `MATERIALIZED`-paden (`original_ids`, `phaseb_ids`, `original_grid`, `phaseb_grid`) die dezelfde nationale locatie-/connectorselectie en aggregatie deels **opnieuw** doen. Daarnaast kunnen de extra kolommen en anders opgestelde bounds in de fase-B-query een **ander fysiek PostgreSQL-plan** opleveren. De exacte bottleneck is **nog niet gemeten** en mag niet uit de 5s-timeout worden afgeleid.

### Eerst diagnostiek: alleen planner, géén nationale SQL-uitvoering

Nieuw CLI-commando `--emit-overview-phaseb-plan` extraheert uit de P2m-generator **de daadwerkelijke fase-B-query met dezelfde nationale CCS2/DC-fixture en uitgelijnde bbox**, maar laat uitsluitend `EXPLAIN (VERBOSE, SETTINGS)` draaien. Geen `ANALYZE`, dus **geen uitvoering van de landelijke query**. Hiermee onderzoeken we het geplande scan-/joinpad zonder opnieuw de viervoudige vergelijking te starten.

**Windows:**

```powershell
cd C:\GITHUB\ChargeWatch
git switch feat/map-overview-113-phase-b
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-plan
if ($LASTEXITCODE -ne 0) { throw 'SQL-generatie mislukt; stop.' }
$sqlText = $lines -join "`n"
if (-not $sqlText.Contains('P2m-DIAG: single actual phase-B') -or
    [regex]::Matches($sqlText,'EXPLAIN \(VERBOSE, SETTINGS\)').Count -ne 1 -or
    $sqlText.Contains('EXPLAIN (ANALYZE') -or
    -not $sqlText.Contains('READ ONLY') -or
    -not $sqlText.Contains("statement_timeout = '5s'") -or
    -not $sqlText.Contains('ARRAY[''ccs2'']::text[]') -or
    -not $sqlText.Contains('ARRAY[''dc'']::text[]') -or
    [regex]::IsMatch($sqlText,'\$[1-9][0-9]*')) {
    throw 'Onverwachte P2m-DIAG SQL; stop.'
}
$sqlText | Set-Content .\p2m-phaseb-plan.sql -Encoding utf8
scp .\p2m-phaseb-plan.sql hinthem@192.168.2.48:~/p2m-phaseb-plan.sql
if ($LASTEXITCODE -ne 0) { throw 'SCP-upload mislukt; stop.' }
```

**NAS:**

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2m-phaseb-plan.sql \
  2>&1 | tee ~/p2m-phaseb-plan-result.txt
```

**Belangrijk:** `set -o pipefail` zorgt dat de PostgreSQL-foutstatus niet door `tee` wordt gemaskeerd, en `2>&1` neemt foutmeldingen mee in het log. De gebruiker gebruikte voor de timeout in de getoonde terminal geen zichtbare `pipefail`; dat kan de shell-exitstatus maskeren maar veroorzaakt de timeout zelf niet.

Lever eerst `p2m-phaseb-plan-result.txt` aan. Daarna kunnen we beoordelen of één echte fase-B-SELECT verantwoord binnen 5s kan worden getest of dat de SQL-planner/SQL-vorm eerst moet worden verbeterd. **De gecombineerde P2m-query niet herhalen en de 5s-timeout niet verhogen**. Endpoint blijft uit; geen merge/deploy/DB-wijziging.


## P2m-DIAG Windows-generatorfout en correctie — 4 november 2026

Gebruiker rapporteerde na `--emit-overview-phaseb-plan`:

```text
Unhandled exception. System.InvalidOperationException: P2m template boundaries changed.
   at OverviewPhaseBPlanExporter.Create() ... line 19
```

De eerder toegevoegde plannergenerator probeerde de `phaseb_region`- en `phaseb_grid`-CTE's uit het samengestelde P2m-script te **extraheren door te zoeken naar exacte substringgrenzen en inspringing**. Die tekstmatch was fragiel en faalde bij de daadwerkelijke generatoruitvoer. **Er is geen diagnostische SQL gegenereerd of op de NAS uitgevoerd.** De fout is in de testgenerator, geen PostgreSQL-fout en geen bewijs van een fout in `MapOverviewSql`.

De generator is in commit `826f8a1` aangepast. `OverviewPhaseBPlanExporter.Create()` **gebruikt nu direct `MapOverviewSql.Build(filter)` uit de gekoppelde API-productiebron**, vervangt alleen de zeven bekende, vaste read-only testparameters en voegt zelf de vaste nationale `phaseb_region`-CTE toe. Er is **geen afhankelijkheid meer van P2m-template-witruimte of de vier zware P2m-pariteitsqueries**. Hij genereert één `EXPLAIN (VERBOSE, SETTINGS)` zonder `ANALYZE`, in een read-only transactie met 5s limiet. Bij onverwachte parameters of queryvorm stopt de generator.

**Opnieuw uitvoeren:** haal de laatste branch op en draai eerst `dotnet build`, daarna `dotnet run --no-build ... --emit-overview-phaseb-plan`. Voer de PowerShell-SQL-validatie uit vóór upload. De oude foutcode is vervangen, maar **nog geen geslaagde lokale generatoruitvoer bevestigd**; het queryplan is nog onbekend. De feitelijke API-/Npgsql-binding wordt door deze offline fixture niet bewezen.

**PowerShell-veiligheid:** na een `throw` of mislukte `dotnet run` geen vervolgopdrachten afzonderlijk doorzetten met de mogelijk lege/oude `$lines`. De volgende generatie, veiligheidscontroles en upload moeten in één gecontroleerd blok gebeuren. De inhoudelijke P2m-pariteit blijft OPEN na de eerdere 5s-timeout.


## P2m-DIAG werkelijk plannerresultaat — 4 november 2026: locatie-first en onderschatte bbox

Gebruiker leverde `p2m-phaseb-plan-result.txt` aan. De planning-only query werd succesvol afgewerkt (`SET; BEGIN; SET; SET; SET; EXPLAIN 57 rows; ROLLBACK; RESET`). **Er is geen `ANALYZE` uitgevoerd, dus deze uitvoer bevat geen runtime en geen werkelijke rijtellingen.**

Het opvallende plan:

- `CTE selected -> Nested Loop Semi Join`, geschatte **1 geselecteerde rij** (`rows=1`).
- `Index Scan using ix_charging_location_active_position on charging_location l`, schatting **8 locaties** (`rows=8`).
- Voor elke ruwe kandidaat een `Index Scan using idx_evse_active_location on evse e` en daarbinnen `Index Scan using idx_connector_active_evse on connector c`. De landelijke connector-first `Parallel Seq Scan on connector` uit P2k ontbreekt dus in dit plan.
- De rechthoek wordt in **vier InitPlans** uit `phaseb_region.shape` gelezen en vervolgens via `ST_MakeEnvelope((InitPlan2).col1, ...)` als runtime-geografie aan de ruimtelijke index doorgegeven.
- In P2j waren **81.945 actieve locaties** geselecteerd in de nationale complete grid8-dekking; het P2m-DIAG-`EXPLAIN` is een **zeer lage schatting** voor dezelfde soort dekking. De P2k-filteruitvoer had **1.608** CCS2/DC locaties, terwijl `selected` hier `rows=1` wordt geschat.

**Waarschijnlijke verklaring, nog te toetsen:** `ST_MakeEnvelope` gebruikt runtimewaarden uit `InitPlan`-subselects, waardoor de planner een zeer beperkte geometrische selectiviteit inschat. De echte Npgsql-aanroep bindt echter numerieke PostgreSQL-parameters ($1..$7). Het is **niet bewezen** dat deze client-query hetzelfde plan krijgt als de synthetische diagnostiek; ook de gezamenlijke vier-paden P2m-timeout kan hierdoor en/of door duplicatief SQL-werk komen.

### Vervolg P2m-E: dezelfde API-query met echte PostgreSQL bindparameters plannen

Nieuw `--emit-overview-phaseb-prepared-plan` genereert **één `PREPARE cw_phaseb_planner(double precision×5, text[]×2) AS MapOverviewSql.Build(MapFilter)`** gevolgd door **`EXPLAIN (VERBOSE, SETTINGS) EXECUTE cw_phaseb_planner(...) `** voor dezelfde nationale grid8-uitgelijnde bounds en CCS2/DC-filter. Er is **geen `ANALYZE` en dus geen landelijke data-uitvoering**. De gespecificeerde WGS84-bounds horen bij vaste grid8-cellen `x=130..133, y=82..86`, gekozen voor de P2i/P2l Nederlandgerichte rechthoek. De echte API-SQL blijft gedeeld vanuit `src/ChargeWatch.Api/MapOverviewSql.cs`; geen SQL-kopie.

Windows, voer als één blok uit:

```powershell
& {
    cd C:\GITHUB\ChargeWatch
    git switch feat/map-overview-113-phase-b
    if ($LASTEXITCODE -ne 0) { throw 'Branch wisselen mislukt' }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw 'Git pull mislukt' }
    $project='tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
    dotnet build $project --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Build mislukt' }
    $lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-prepared-plan
    if ($LASTEXITCODE -ne 0) { throw 'P2m-E SQL-generatie mislukt' }
    $sqlText=$lines -join "`n"
    if ([regex]::Matches($sqlText,'EXPLAIN \(VERBOSE, SETTINGS\)').Count -ne 1 -or
        $sqlText.Contains('EXPLAIN (ANALYZE') -or
        -not $sqlText.Contains('PREPARE cw_phaseb_planner') -or
        -not $sqlText.Contains('READ ONLY') -or
        -not $sqlText.Contains("statement_timeout = '5s'") -or
        -not $sqlText.Contains("ARRAY[''ccs2'']::text[]") -or
        -not $sqlText.Contains("ARRAY[''dc'']::text[]")) {
        throw 'Onverwachte P2m-E SQL, stop'
    }
    $sqlText | Set-Content .\p2m-phaseb-prepared-plan.sql -Encoding utf8
    scp .\p2m-phaseb-prepared-plan.sql hinthem@192.168.2.48:~/p2m-phaseb-prepared-plan.sql
    if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt' }
}
```

NAS:

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2m-phaseb-prepared-plan.sql \
  2>&1 | tee ~/p2m-phaseb-prepared-plan-result.txt
```

Stuur **`p2m-phaseb-prepared-plan-result.txt`** terug. Beoordeel eerst of het plan terugschakelt naar connector-first of nog steeds na een schatting van enkele geografische locaties een EVSE-/connectornested-loop gebruikt. **Niet** opnieuw de landelijke gecombineerde P2m-`ANALYZE`-pariteit uitvoeren vóór we weten welk plan echte gebonden parameters opleveren.

**Reikwijdte:** PREPARE/EXPLAIN EXECUTE laat de PostgreSQL-planner met gebonden waarden werken, maar blijft een planning-only diagnose. Het bewijst geen Npgsql-/HTTP-end-to-end-latentie en geen echte populatie-/statuspariteit. De aanvankelijke P2m-pariteitstest blijft OPEN na timeout; geen wijzigingen aan productie-`MapOverviewSql`, geen deployment, merge of databasewijziging.


## P2m-E succesvol: geparametriseerd API-plan is connector-first (4 november 2026)

Gebruiker leverde de volledige `EXPLAIN (VERBOSE, SETTINGS) EXECUTE cw_phaseb_planner(...)` van de echte parameterized SQL aan. PostgreSQL kiest:

1. `Gather` met **2 workers gepland**, `Parallel Seq Scan on public.connector c` met de **CCS2/DC**-predicaten.
2. `Index Scan using evse_pkey` naar `evse` en `HashAggregate` van de bijbehorende `location_id`.
3. `Index Scan using charging_location_pkey` naar `charging_location` inclusief de complete-landelijke bbox.
4. Daarna `Nested Loop Left Join` naar actieve EVSE's voor de `per_location`-aggregatie, en tenslotte `GroupAggregate` op de rastercellen.

**Beoordeling:** dit is **connector-first** zoals de eerder snelle P2k-uitvoering. De vorige P2m-DIAG-locatie-first-strategie was gekoppeld aan de **subquery-gebaseerde bbox-parameterisering** van die diagnose en mag niet aan de echte API-parameterquery worden toegeschreven. De schatting van **5 geselecteerde locaties** blijft onrealistisch tegenover de eerdere **1.608 werkelijke locaties**; dat maakt de schatting onbetrouwbaar, maar veroorzaakt in dit plan niet de ongunstige geo-first-join. **Deze `EXPLAIN` is planning-only**: geen echte runtime/rijaantallen en geen P2m-ID/statuspariteit.

**Besluit:** productie-`MapOverviewSql` **niet** wijzigen en geen nieuwe indexes/planhints/geo-first-dispatch invoeren op basis van de eerdere 5s-timeout. De gecombineerde P2m-vergelijking bevatte vier dure subpaden en is niet representatief voor één API-call. Valideer de afzonderlijke werkelijke SQL-uitvoering voordat we gerichte verdere contract-/statuscontroles kiezen.

### P2m-F: één echte read-only fase-B-query onder 5s

Nieuwe generator `--emit-overview-phaseb-execution` hergebruikt **letterlijk de reeds met P2m-E geverifieerde `PREPARE` + parameterwaarden** en verandert uitsluitend `EXPLAIN (VERBOSE, SETTINGS)` naar `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, SUMMARY)`. Dit voert de **enkele SELECT** daadwerkelijk uit zodat we de echte geselecteerde locaties, rasterrijen, gebruikte indexes en timing kunnen aflezen. Er is **geen** viervoudige vergelijking of herhaalde landelijke scan in dit script. `SET ROLE chargewatch_read`, `READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `ROLLBACK` blijven aanwezig. Deze ene diagnostische uitvoering is **nog niet gedaan** en bewijst op zichzelf nog geen ID/statuspariteit.

**Windows (één gecontroleerd PowerShell-blok):**

```powershell
& {
    cd C:\GITHUB\ChargeWatch
    git switch feat/map-overview-113-phase-b
    if ($LASTEXITCODE -ne 0) { throw 'Branch wisselen mislukt' }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw 'Git pull mislukt' }

    $project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
    dotnet build $project --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Probe-build mislukt' }

    $lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-execution
    if ($LASTEXITCODE -ne 0) { throw 'SQL-generatie mislukt' }
    $sqlText = $lines -join "`n"

    if (-not $sqlText.Contains('P2m-F: ONE bounded read-only phase-B execution') -or
        [regex]::Matches($sqlText,'EXPLAIN \(ANALYZE, BUFFERS, SETTINGS, SUMMARY\)').Count -ne 1 -or
        -not $sqlText.Contains('SET ROLE chargewatch_read;') -or
        -not $sqlText.Contains('READ ONLY') -or
        -not $sqlText.Contains("statement_timeout = '5s'") -or
        -not $sqlText.Contains("ARRAY[''ccs2'']::text[]") -or
        -not $sqlText.Contains('ROLLBACK;')) {
        throw 'Onverwachte SQL, stop'
    }
    $sqlText | Set-Content .\p2m-phaseb-execution.sql -Encoding utf8
    scp .\p2m-phaseb-execution.sql hinthem@192.168.2.48:~/p2m-phaseb-execution.sql
    if ($LASTEXITCODE -ne 0) { throw 'SCP-upload mislukt' }
}
```

**NAS:**

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2m-phaseb-execution.sql \
  2>&1 | tee ~/p2m-phaseb-execution-result.txt
```

Stuur de resultaatoutput terug. **Bij timeout of fout: stop en analyseer**, niet opschalen van de timeout. **Bij PASS:** vergelijk werkelijke `CTE selected`-rijtelling, `per_location`, uiteindelijke rasterrijen, `Execution Time`, `Buffers` en eventueel sort/hash spill. Een complete ID-/availability-pariteitstest blijft afzonderlijk te beoordelen, zonder weer een vierdubbele landelijke query af te dwingen.

**Geen productie-endpoint activeren, merge, NAS-deploy of DB-wijzigingen.**

## P2m-F — werkelijke geparametriseerde fase-B-executie: performance PASS (4 november 2026)

De gebruiker heeft de volledige NAS-uitvoer van `--emit-overview-phaseb-execution` aangeleverd. Er is **één** feitelijke `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, SUMMARY) EXECUTE cw_phaseb_planner(...)` uitgevoerd, niet de eerder getime-outte gecombineerde vier-CTE-pariteitstest. `SET ROLE chargewatch_read`, `REPEATABLE READ READ ONLY`, 5s statement-timeout en `jit=off` waren onderdeel van het script.

| Grootheid | Waarneming |
|---|---:|
| Planning Time | **22,039 ms** |
| Execution Time | **256,378 ms** |
| `CTE selected` werkelijke locaties | **1.612** (planner: **5**) |
| Finale `per_location`-rijen | **1.612** |
| EVSE-joinrijen voor beschikbaarheidsaggregatie | **9.329** |
| Resultaat: niet-lege gridLevel8-rastercellen | **11** |
| Technisch passende connectorrijen na parallel scan | **6.485** |
| Unieke connector-EVSE-ID's na hashaggregatie | **6.479** |
| Location-ID's na technische filterkwalificatie | **1.640**, daarna **1.612** binnen actieve geografische selectie |
| `idx_evse_active_location` lookups | **1.612** |
| Root shared buffers | `hit=35556 read=34202 dirtied=799 written=4961` |
| Temp spill / disk sort | **niet gemeld**; hashaggregaties één batch en sorts in geheugen |

**Plan:** `Gather` + `Parallel Seq Scan on connector` (2 workers gestart) → `evse_pkey` → `charging_location_pkey` → `idx_evse_active_location` → `per_location` → `GroupAggregate`. Dit is de verwachte **connector-first**-strategie. De geschatte vijf geselecteerde locaties versus werkelijk 1.612 blijven een planner-cardinaliteitsprobleem, maar veroorzaken hier geen slechte fysieke strategie. Het omvangrijke `shared read`-profiel, met name bij de connector-scan, moet bij latere herhalingen/concurrentie worden gevolgd. `dirtied/written` in een read-only EXPLAIN bewijst **geen applicatie-DML**; PostgreSQL kan ook tijdens leesverwerking paginahints markeren en buffers wegschrijven.

De oude afzonderlijke P2k-reference mat **265,966 ms** met **1.608** locaties en 11 cellen op een **eerder** snapshot. Het tijdverschil is geen bewezen structurele winst; vier extra locaties betekenen zonder gelijke snapshot **geen bewezen pariteitsfout**.

**Besluit:** **P2m-F performance-gate PASS voor de nationale CCS2/DC-grid8-fixture onder 5s**, geen reden tot index-/querywijziging of timeoutverhoging. **P2m correctness blijft OPEN**: `EXPLAIN ANALYZE` bewijst geen exacte ID-, celcount- of statussegmentpariteit met de oude overview. Volgende stap is een **begrensde pariteitsgate met gedeeld read-only snapshot**: eerst exacte `id,cx,cy`-selectie en volledige rasterstatuswaarden tegen de oorspronkelijke referentie vergelijken, zonder vier dubbel uitgevoerde zware CTE's en zonder synthetische bbox-subqueries die een onrepresentatief geo-first-plan afdwingen. Separaat blijven HTTP/Npgsql, echte Android-markerkwaliteit, 500-object-budget en fase-C-caching open. Geen merge, NAS-deploy, DB- of NDW-wijzigingen; featureflag blijft uit.

## P2m-G1 — beperkte geparametriseerde locatie-/celpariteit (voorbereid 4 november 2026)

Na P2m-F (**256,378 ms**, **1.612** locaties, **11** rastercellen, connector-first) is een nieuwe **enkelvoudige** ID-/celvergelijking voorbereid in `tools/MapFilterQ4Probe/OverviewPhaseBGIdParityExporter.cs` met CLI-argument `--emit-overview-phaseb-g1-ids`.

**Reden voor opsplitsen:** de oude P2m-pariteitsproef draaide vier zware `MATERIALIZED` takken met statusaggregaties en een synthetische `phaseb_region` als scalaire bbox-subquery. Daardoor kon de planner een andere, onrepresentatieve locatie-first-route kiezen en trad een 5s-timeout op. P2m-G1 genereert één `PREPARE` + één `EXECUTE` met **dezelfde numerieke bindwaarden als P2m-E/F** voor de fase-B-selectie, naast de al gevalideerde P2l-referentieselectie. Beide zijn direct afgeleid van `MapFilter` en `MapOverviewSql.Build(MapFilter)`. **Geen** per-locatie status-/EVSE-aggregatie, en elke geselecteerde CTE wordt slechts eenmaal gematerialiseerd.

Eén read-only `REPEATABLE READ`-transactie geeft een gedeelde snapshot voor beide vergeleken routes. De SQL rapporteert:

- `original_locations` en `phaseb_locations`;
- `original_distinct_ids` en `phaseb_distinct_ids` (detectie van dubbele locatie-ID's);
- `original_only_id_cells` en `phaseb_only_id_cells` via volledige bidirectionele `EXCEPT (id,cx,cy)`;
- `invalid_assignments` voor null-ID/celcoördinaten;
- `id_cell_parity_matches` moet **t** zijn, alle verschillen/invaliden **0**, en aantallen onderling gelijk (aantal kan door een nieuwe NDW-snapshot verschillen van 1.612).

**Belangrijke nuance:** G1 bewijst bij PASS **alleen exacte ID-/gridcelpariteit voor deze fixture**. Statussegmenten en individuele kaart-/Android countQuality zijn nog niet getoetst. Ook een `PREPARE` in deze samengestelde check garandeert niet hetzelfde queryplan als één API-call; P2m-F is daarvoor het eerdere, afzonderlijke performancebewijs. Beide selecties gebruiken hetzelfde complete grid8-Nederlandgericht gebied, maar de correcte numerieke grensgelijkheid moet in de pariteitsuitkomst worden bevestigd.

### Windows — precies één G1-script maken en uploaden

Voer het volgende **in één PowerShell-blok** uit. Na een fout niet verdergaan met losse volgende commando's.

```powershell
& {
    cd C:\GITHUB\ChargeWatch
    git switch feat/map-overview-113-phase-b
    if ($LASTEXITCODE -ne 0) { throw 'Branch wisselen mislukt' }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw 'Git pull mislukt' }

    $project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
    dotnet build $project --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Probe-build mislukt' }

    $lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-g1-ids
    if ($LASTEXITCODE -ne 0) { throw 'P2m-G1 SQL-generator mislukt' }
    $sqlText = $lines -join "`n"

    if (-not $sqlText.Contains('P2m-G1: ONE bounded read-only') -or
        ([regex]::Matches($sqlText, 'PREPARE cw_phaseb_g1\(').Count -ne 1) -or
        ([regex]::Matches($sqlText, 'EXECUTE cw_phaseb_g1\(').Count -ne 1) -or
        -not $sqlText.Contains('SET ROLE chargewatch_read;') -or
        -not $sqlText.Contains('REPEATABLE READ READ ONLY') -or
        -not $sqlText.Contains("statement_timeout = '5s'") -or
        -not $sqlText.Contains('id_cell_parity_matches') -or
        -not $sqlText.Contains('ROLLBACK;') -or
        $sqlText.Contains('EXPLAIN (ANALYZE')) {
        throw 'Onverwachte P2m-G1 SQL; stop'
    }

    $sqlText | Set-Content .\p2m-g1-id-parity.sql -Encoding utf8
    scp .\p2m-g1-id-parity.sql hinthem@192.168.2.48:~/p2m-g1-id-parity.sql
    if ($LASTEXITCODE -ne 0) { throw 'SCP-upload mislukt' }
}
```

### NAS — één read-only SELECT

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2m-g1-id-parity.sql \
  2>&1 | tee ~/p2m-g1-id-parity-result.txt
```

Het script zet `chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout=5s`, `lock_timeout=1s` en `jit=off`, en sluit af met `ROLLBACK`/`RESET ROLE`. Bij timeout of mismatch **stoppen en eerst de output beoordelen**, niet automatisch herhalen met een langere timeout. Geen index, schema, API, NDW, Android of DB-mutatie.

**Pas na G1-PASS:** P2m-G2 aparte read-only vergelijking van de **volledige vier status-/locatiecount-kolommen per rastercel**, binnen een nieuwe gedeelde snapshot per vergelijkingsstatement. Dit is bewust niet in G1 ingebouwd, zodat de eerder gefaalde zware vier-routepariteit niet terugkeert. G1 is **voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd**; geen PASS claimen.

PR #114/#115/#116 draft; geen merge/deploy/endpointactivatie.

## P2m-G1 daadwerkelijk PASS en P2m-G2 voorbereid — 4 november 2026

Gebruiker leverde de werkelijke uitvoer van de read-only G1-query:

| Veld | Waarde |
|---|---:|
| `original_locations` | 1.612 |
| `original_distinct_ids` | 1.612 |
| `phaseb_locations` | 1.612 |
| `phaseb_distinct_ids` | 1.612 |
| `original_only_id_cells` | 0 |
| `phaseb_only_id_cells` | 0 |
| `invalid_assignments` | 0 |
| `id_cell_parity_matches` | **t** |

**G1 PASS** voor CCS2/DC, landelijke complete gridLevel8-bounds, hetzelfde PostgreSQL-snapshot. Dit bewijst volledige `(id,cx,cy)`-setpariteit en unieke locatietoewijzing in deze test. Het bewijst **nog niet** de per-cel beschikbaarheidsaantallen of legacy-/Android-countQuality.

### P2m-G2 — twee volledige rasteraggregaties, één read-only snapshot

Nieuwe generator: `tools/MapFilterQ4Probe/OverviewPhaseBGGridParityExporter.cs`. CLI: `--emit-overview-phaseb-g2-grid`. Er wordt één **geparametriseerde** `PREPARE/EXECUTE` gebouwd, met exact de numerieke, complete grid8-celgrenzen van G1/F en productie-`MapFilter` (`ccs2`/`dc`). Vergeleken worden de originele P2l-overview en `MapOverviewSql.Build(MapFilter)`, zonder de twee extra ID-only-CTE's uit de oude mislukte vierpadentest.

Elk van de beide rasterresultaten wordt eenmaal als `MATERIALIZED`-CTE berekend; `EXCEPT` controleert in **beide** richtingen alle zes velden: `cx,cy,location_count,locations_with_free,locations_with_no_free,locations_unknown`. Fase-B `int`-tellingen worden voor de vergelijking expliciet naar `bigint` gecast; de oorspronkelijke productiesemantiek blijft ongewijzigd.

**Output:**

- `original_cells`, `phaseb_cells`: niet-lege rastercellen;
- `original_locations`, `phaseb_locations`: som van `location_count`;
- `original_only_grid_rows`, `phaseb_only_grid_rows`: bidirectionele afwijkingen van alle zes velden;
- `original_duplicate_cells`, `phaseb_duplicate_cells`: niet-unieke `cx,cy`;
- `original_invalid_partitions`, `phaseb_invalid_partitions`: onmogelijke/NULL/negatieve aantallen of `free+noFree+unknown != locationCount`;
- `grid_parity_matches`: **t** uitsluitend als beide grids volledig identiek zijn, beide geen invalide statuspartities of dubbele cellen bevatten, en cellen-/locatiesommen overeenkomen.

**PASS:** `original_cells=phaseb_cells` (eerder 11); `original_locations=phaseb_locations` (eerder 1.612 maar live snapshot kan afwijken); alle zes fout-/verschiltellingen nul; `grid_parity_matches=t`. G2 is **voorbereid, nog niet lokaal gecompileerd of op NAS uitgevoerd**.

### Windows — genereren en uploaden

Voer als één PowerShell-blok uit:

```powershell
& {
    cd C:\GITHUB\ChargeWatch
    git switch feat/map-overview-113-phase-b
    if ($LASTEXITCODE -ne 0) { throw 'Branch wisselen mislukt' }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw 'Git pull mislukt' }

    $project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
    dotnet build $project --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Probe-build mislukt' }

    $lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-g2-grid
    if ($LASTEXITCODE -ne 0) { throw 'P2m-G2 generator mislukt' }
    $sqlText = $lines -join "`n"

    if (-not $sqlText.Contains('P2m-G2: ONE bounded read-only') -or
        ([regex]::Matches($sqlText, 'PREPARE cw_phaseb_g2\(').Count -ne 1) -or
        ([regex]::Matches($sqlText, 'EXECUTE cw_phaseb_g2\(').Count -ne 1) -or
        -not $sqlText.Contains('SET ROLE chargewatch_read;') -or
        -not $sqlText.Contains('REPEATABLE READ READ ONLY') -or
        -not $sqlText.Contains("statement_timeout = '5s'") -or
        -not $sqlText.Contains("lock_timeout = '1s'") -or
        -not $sqlText.Contains('grid_parity_matches') -or
        -not $sqlText.Contains('ROLLBACK;') -or
        $sqlText.Contains('EXPLAIN (ANALYZE')) {
        throw 'Onverwachte P2m-G2 SQL; stop'
    }

    $sqlText | Set-Content .\p2m-g2-grid-parity.sql -Encoding utf8
    scp .\p2m-g2-grid-parity.sql hinthem@192.168.2.48:~/p2m-g2-grid-parity.sql
    if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt' }
}
```

### NAS — één gecontroleerde read-only uitvoering

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2m-g2-grid-parity.sql \
  2>&1 | tee ~/p2m-g2-grid-parity-result.txt
```

Bij fout of 5s-timeout **geen timeoutverhoging of automatische herhaling**; eerst het resultaat beoordelen. Het script bevat `SET ROLE chargewatch_read`, `REPEATABLE READ READ ONLY`, `lock_timeout=1s`, `statement_timeout=5s`, `jit=off`, `ROLLBACK` en `RESET ROLE`. Dit voert **geen** write, index-/schemawijziging, deployment of API-activatie uit. De productiequery is ongewijzigd.

**Na G2 PASS:** geparametriseerde fase-B-SQL heeft voor deze fixture ID/celcount/status-aggregaatpariteit met P2l. Daarna een apart doelgerichte review van de bestaande legacy-/Android-markerkwaliteit, HTTP-/Npgsql-contract en het fase-C-objectbudget; niet opnieuw generiek Q1–Q4 of NDW-statussemantiek heronderzoeken. Geen merge zonder expliciete toestemming.

## P2m-G2 daadwerkelijk PASS — landelijke CCS2/DC-rasterpariteit (4 november 2026)

De gebruiker heeft de G2-read-only PostgreSQL-uitvoer aangeleverd voor de nationale, complete grid8-celafbakening met `connectorType=ccs2&mode=dc`:

| Controleveld | Werkelijke waarde |
|---|---:|
| `original_cells` / `phaseb_cells` | **11 / 11** |
| `original_locations` / `phaseb_locations` | **1.612 / 1.612** |
| `original_only_grid_rows` / `phaseb_only_grid_rows` | **0 / 0** |
| `original_duplicate_cells` / `phaseb_duplicate_cells` | **0 / 0** |
| `original_invalid_partitions` / `phaseb_invalid_partitions` | **0 / 0** |
| `grid_parity_matches` | **t** |

**Resultaat: G2 PASS.** Voor deze fixture zijn in dezelfde DB-snapshot alle zes rasterwaarden `(cx,cy,location_count,locations_with_free,locations_with_no_free,locations_unknown)` tussen de oorspronkelijke P2l-referentie en de werkelijk geparametriseerde fase-B-SQL identiek. Samen met **G1 PASS** (1.612 unieke locatie-ID's en nul afwijkende `id,cx,cy`) en **F performance PASS** (256,378 ms, connector-first) sluit dit de *nationale CCS2/DC SQL-pariteit* af. De gelijke snapshots slaan op de vergelijking **binnen elke probe**, niet op alle afzonderlijke F/G1/G2-runs onderling.

**Resterende, gescheiden productiegates:** bestaande viewport/Android-markerkwaliteit (`countQuality`, 0/0, onclassificeerbare operationele EVSE en combinatie vrij+onbekend), HTTP/Npgsql-binding en JSON, dynamisch objectbudget van 500, grid-overlap/versie/cache, andere zoom-/filterfixtures en werkelijke concurrentie/p95. **Geen claim** dat P2l- en fase-B-statussemantiek vanzelf gelijk is aan legacy-Android: P2l is zelf een benchmarkreferentie. Concreet codeverschil voor gerichte review: `ApiDatabase.GetMapViewport` geeft numerieke `available_count`/`total_count` (met distinct EVSE's), terwijl `MapOverviewSql` een `unclassified_evses`-criterium toepast en `MapOverviewDatabase.ReadAsync` bij singleton-unknown beide aantallen `null` maakt. Besluit niet stilzwijgend dat deze afwijking een bug of wenselijke nieuwe statusregel is. Eerst een kleine lees-/fixturepariteitscontrole en een expliciet akkoord op het te behouden bestaande UI-gedrag.

**Geen productiecodewijziging, geen DB-migratie/index/NDW-/Androidwijziging, geen API-activatie, geen merge/deployment.** PR #114/#115/#116 blijven draft.

## P2m-H1 — landelijke bestaande-Android-/fase-B-markersemantiek (voorbereid 4 november 2026)

Na G2-PASS volgt **geen herhaling van P2l of Q1–Q4**, maar een afzonderlijke audit van de daadwerkelijk bestaande **ChargeWatch API + Android-markerlogica** tegenover de nieuwe fase-B-overview. Nieuwe exporter: `tools/MapFilterQ4Probe/OverviewPhaseBHMarkerSemanticsExporter.cs`; CLI: `--emit-overview-phaseb-h1-marker-audit`.

### Wat de broncode feitelijk doet

- `src/ChargeWatch.Api/ApiDatabase.cs`, `GetMapViewport`: `availableCount = count(DISTINCT e.id) FILTER (WHERE upper(e.status)='AVAILABLE')`; `totalCount = count(DISTINCT e.id) FILTER (WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED'))`.
- `android/.../data/ndw/ChargeWatchMapClient.kt`: parseert `availableCount` en `totalCount` als **verplichte numerieke velden** en markeert deze voor de ChargeWatch API als `CountReliability.Safe`.
- `android/.../data/ndw/NdwModels.kt`: `available > 0` → groen, anders `total > 0` → rood, anders onbekend; zolang de getallen aanwezig zijn is het label **`available/total`**, dus bij `0/0` ook letterlijk `0/0` (kleur grijs).
- `src/ChargeWatch.Api/MapOverviewSql.cs`: `AVAILABLE` met `btrim`; markeert een locatie als `locationsUnknown` indien geen operationele EVSE **of** minstens één EVSE een niet-herkende operationele status heeft. Ook een reeds beschikbare locatie met een extra onbekende status zou daardoor niet meer als groen tellen.
- `MapOverviewDatabase.ReadAsync`: bij een singleton `unknown` worden `freeCount` en `totalCount` **null**, waarna nieuwe Android-rendering (nog niet geschreven) volgens het contract waarschijnlijk `?` kan tonen.
- De oorspronkelijke Android-clusters aggregeren nu de **EVSE-capaciteit** en gebruiken één clusterkleur; fase C/D voorziet een **ringverdeling over locaties**. Die twee presentatievormen zijn bewust niet één-op-één vergelijkbaar. **H1 controleert uitsluitend individuele locatie-status en getal/label**, niet de toekomstige clusterrenderer.
- De directe NDW-GeoJSON-fallback heeft aparte `Ambiguous/Estimated`-regels en maakt **geen** deel uit van H1.

### Hoe H1 werkt en wat het niet bewijst

De generator gebruikt de **exacte fase-B-locatieselectie** uit de gekoppelde productie-`MapOverviewSql.Build(MapFilter.None)`, met de G1/F-grid8-complete-landelijke grenzen als echte PostgreSQL `$1..$5`-bindwaarden. Er is één `selected AS MATERIALIZED` en één `per_location AS MATERIALIZED`, geen viervoudige nationale vergelijking. **Dezelfde operationele EVSE's** worden gebruikt voor beide tegen elkaar af te zetten count-/classificatieformules, binnen één `REPEATABLE READ READ ONLY`-snapshot.

De oude SQL telt DISTINCT EVSE-ID's na een connector-LEFT JOIN; de audit laat die kostbare connectorjoin weg, maar **alle EVSE-ID's zijn al uniek in de overgebleven join**, zodat de `count(DISTINCT e.id)`-tellingen mathematisch gelijk zijn. Daardoor is H1 een gerichte **count/color-semantiekproef**, geen HTTP/Npgsql-/Android end-to-end-test en geen bewijs van connector-json-pariteit.

Alle metrics zijn **aantallen laadlocaties**, geen aantallen laadpunten:

- `audited_locations`, `legacy_green/red/unknown`, `phaseb_green/red/unknown`;
- `changed_color` en exacte transities `green_to_unknown`, `red_to_unknown`, `red_to_green`, `green_to_red`, `unknown_to_known`;
- `available_count_differences` (`upper(status)` versus `upper(btrim(status))`) en `total_count_differences`;
- `locations_with_unclassified_evses`, `unclassified_and_legacy_free`, `locations_with_null_status`, `locations_with_whitespace_status`;
- `legacy_zero_total_new_null_counts`: locaties die via de huidige Android-client **`0/0`** tonen, terwijl fase B beide counts **`null`** maakt (een nieuwe renderer die de bestaande `AvailabilitySummary` gebruikt zou dan **`?`** tonen);
- `count_invariants_match` moet `t` zijn.

**Geen vooraf verzonnen PASS-grens voor `changed_color`.** Ook als G2-pariteit volledig PASS is, kan H1 wezenlijke afwijkingen tonen. De cijfers bepalen of we de fase-B-classificatie moeten afstemmen op de bestaande Android-ervaring. Bepaal beleidswijzigingen pas na analyse en expliciet akkoord.

### Windows — één H1-script genereren en uploaden

```powershell
& {
    cd C:\GITHUB\ChargeWatch
    git switch feat/map-overview-113-phase-b
    if ($LASTEXITCODE -ne 0) { throw 'Branch wisselen mislukt' }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw 'Pull mislukt' }

    $project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
    dotnet build $project --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Probe-build mislukt' }

    $lines = dotnet run --no-build --project $project -- --emit-overview-phaseb-h1-marker-audit
    if ($LASTEXITCODE -ne 0) { throw 'P2m-H1 generator mislukt' }
    $sqlText = $lines -join "`n"

    if (-not $sqlText.Contains('P2m-H1: ONE bounded read-only') -or
        ([regex]::Matches($sqlText, 'PREPARE cw_phaseb_h1\(').Count -ne 1) -or
        ([regex]::Matches($sqlText, 'EXECUTE cw_phaseb_h1\(').Count -ne 1) -or
        -not $sqlText.Contains('SET ROLE chargewatch_read;') -or
        -not $sqlText.Contains('REPEATABLE READ READ ONLY') -or
        -not $sqlText.Contains("statement_timeout = '5s'") -or
        -not $sqlText.Contains("lock_timeout = '1s'") -or
        -not $sqlText.Contains('changed_color') -or
        -not $sqlText.Contains('count_invariants_match') -or
        -not $sqlText.Contains('ROLLBACK;') -or
        $sqlText.Contains('EXPLAIN (ANALYZE')) {
        throw 'Onverwachte P2m-H1 SQL; stop'
    }

    $sqlText | Set-Content .\p2m-h1-marker-audit.sql -Encoding utf8
    scp .\p2m-h1-marker-audit.sql hinthem@192.168.2.48:~/p2m-h1-marker-audit.sql
    if ($LASTEXITCODE -ne 0) { throw 'SCP-upload mislukt' }
}
```

### NAS — één read-only landelijke audit

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -P expanded=on \
  -U chargewatch_owner -d chargewatch < ~/p2m-h1-marker-audit.sql \
  2>&1 | tee ~/p2m-h1-marker-audit-result.txt
```

Het script zet `chargewatch_read`, `READ ONLY`/`REPEATABLE READ`, `jit=off`, 5s statement-timeout en 1s lock-timeout en eindigt met `ROLLBACK`/`RESET ROLE`. **Eerst lokaal builden; bij buildfout stoppen.** Bij timeout of SQL-fout ook stoppen; geen automatische timeoutverhoging of herhaling. De landelijke selectie wordt niet afgerond op 999 en verandert geen databasegegevens.

**Status:** P2m-H1-code en uitvoerprocedure **voorbereid**. Niet lokaal gecompileerd/gedraaid, geen NAS-uitvoer of PASS-claim, geen productiecode-/Android-/NDW- of DB-wijzigingen, geen deployment of merge.

## P2m-H1 werkelijk uitgevoerd — substantiële legacy-markerregressie gevonden (4 november 2026)

Gebruiker heeft de volledige read-only nationale, ongefilterde H1 NAS-audit aangeleverd voor **82.463** locaties in de complete grid8-Nederlandgerichte selectie.

| Metriek | Werkelijke waarde |
|---|---:|
| `audited_locations` | **82.463** |
| `legacy_green` / `legacy_red` / `legacy_unknown` | **70.865 / 11.589 / 9** |
| `phaseb_green` / `phaseb_red` / `phaseb_unknown` | **69.565 / 8.228 / 4.670** |
| `changed_color` | **4.661** (**5,65%** locaties) |
| `green_to_unknown` | **1.300** |
| `red_to_unknown` | **3.361** |
| `red_to_green` / `green_to_red` / `unknown_to_known` | **0 / 0 / 0** |
| `available_count_differences` | **0** |
| `total_count_differences` | **0** |
| `locations_with_unclassified_evses` | **4.661** |
| `unclassified_and_legacy_free` | **1.300** |
| `locations_with_null_status` / `locations_with_whitespace_status` | **0 / 0** |
| `legacy_zero_total_new_null_counts` | **9** |
| `count_invariants_match` | **t** |

**Conclusie:** H1 is **geslaagd als meetinstrument**, maar de bestaande fase-B-classificatie is **NIET functioneel equivalent** aan huidige ChargeWatch-Android-markerkleuren. Alle 4.661 kleurafwijkingen zijn locaties met één of meer EVSE's waarvan de status niet in fase-B's expliciete lijst valt. Fase-B promoveert deze locaties naar *unknown* ondanks exact dezelfde vrije/totale EVSE-tellingen als de legacy-API. Vooral 1.300 locaties die nu bewezen een vrije aansluiting hebben zouden groen verliezen. De overige 3.361 zouden rood verliezen. Er is geen bewijs voor een datatelfout; het probleem zit in **de locatiebrede unknown-overschrijfregel**.

**Voorgestelde (nog NIET geïmplementeerde) compatibiliteitssemantiek:** per geselecteerde locatie precies de huidige Android/ChargeWatch-regel toepassen:

- `free_evses > 0` → `locations_with_free`;
- `free_evses = 0 AND total_evses > 0` → `locations_with_no_free`;
- `total_evses = 0` → `locations_unknown`.

Een operationele EVSE met een niet-herkende status blijft daarbij meetellen in `total_evses` maar **maakt niet automatisch de hele locatie unknown**. Dit is conform de bestaande `GetMapViewport` + `AvailabilitySummary.status`-kleurkeuze. Bestaande `0/0`-locaties (9) blijven visueel unknown; open keuze is of nieuwe singleton-markers `0/0` moeten tonen i.p.v. `?`, bijvoorbeeld `AvailableCount=0, TotalCount=0` met een expliciet kwaliteitsoordeel. Houd eventuele onzekerheidsmetadata gescheiden van groen/rood markerstatus; herdefinieer geen NDW-statussen.

**Gates na expliciet akkoord op compatibiliteitsregel:** kleine geïsoleerde wijziging in `MapOverviewSql` en zo nodig singleton countcontract, bijpassende referentie-/testfixtures actualiseren (oude P2l/H1-metingen bewaren als historisch referentiepunt), lokale build/selftests, read-only nationale kleur-/aantalpariteit met huidige Android-regel (nul onverwachte kleurafwijkingen en nul countverschillen), en vernieuwde gefilterde gridpariteit/performance. F/G1/G2 zijn **historisch PASS op de oude fase-B-classificatie** en bewijzen niet automatisch de juistheid van gewijzigde SQL. Niet automatisch mergen of deployen.

**Productiecode niet aangepast**; alleen diagnose en documentatie. #114/#115/#116 blijven draft, `CW_ENABLE_MAP_OVERVIEW` uit, geen database-/NDW-/schemawijziging.

## H1-compatibiliteitscorrectie geïmplementeerd; H2-hermeting OPEN (4 november 2026)

**Expliciet gebruikersakkoord op voorstel na H1:** behoud huidige ChargeWatch Android-markerregel, en behoud `0/0` bij nul operationele EVSE's. De implementatie staat **alleen op** `feat/map-overview-113-phase-b` (draft PR #116), zonder merge, API-activering, databasewijziging of deployment.

### Implementatie

- `MapOverviewSql.Build(MapFilter)` classificeert nu per **locatie** met `free_evses > 0` → groen; anders `total_evses > 0` → rood; anders unknown. `unclassified_evses` is verwijderd uit de locatiebrede statusbeslissing én uit de onnodige per-locatieaggregatie. Onbekende operationele EVSE-statussen tellen nog steeds mee in de `total_evses`.
- De `free_evses`-predicaat is nu precies `upper(e.status)='AVAILABLE'`, gelijk aan de bestaande ChargeWatch-API `available_count`. Het `total_evses`-predicaat is ongewijzigd. Er komt geen connectorjoin in de EVSE-statusaggregatie; iedere EVSE wordt precies eenmaal geteld.
- `MapOverviewLocationMarker.FromCounts` valideert `0<=available<=total` en bewaart beide tellerwaarden **altijd numeriek**, ook bij `0/0`. `CountQuality` blijft bij `0/0` `"unknown"` om aan te geven dat de *beschikbaarheidskleur* onbekend blijft, terwijl de aantallen feitelijk nul zijn.
- `MapOverviewDatabase.ReadAsync` gebruikt die factory voor singletonmarkers in plaats van counts op `null` te zetten. Clusterlocatiepartities worden hierdoor groen/rood/unknown volgens het bestaande Android-contract; de geplande clusterring blijft expliciet gebaseerd op het aantal locaties, niet het aantal aansluitingen.
- Nieuwe `MapOverviewPhaseBCompatibilitySelfTest`, aangeroepen vanuit de bestaande `--self-test`-modus, borgt SQL-predicaten, statuspartitie, `0/0`-JSON en ongeldige getallen.

**Historisch bewijs blijft historisch:** P2m-F/G1/G2 bewezen de oude fase-B-SQL tegenover de oude P2l-benchmark. De **G2-PASS is niet automatisch een PASS voor de gewijzigde statusformule**: bij onbekende operationele EVSE's hoort de nieuwe correcte classifier juist van P2l af te wijken. De oorspronkelijke H1-audit toont de omvang van de voorafgaande regressie (4.661 locaties), maar de **H1-generator bevat nog de oude phase-B-classificatie als historische referentie** en is niet bedoeld als post-fix-acceptatietest. Gebruik H2.

### P2m-H2: echte productie-per-locatie-CTE versus Android-legacy, per locatie-ID

Nieuwe standalone exporter `tools/MapFilterQ4Probe/OverviewPhaseBH2CompatibilityExporter.cs` gebruikt de daadwerkelijk uit `MapOverviewSql.Build(MapFilter)` **geëxtraheerde production `selected` + `per_location` CTE's**, niet een handmatig nagemaakte nieuwe EVSE-teller. De uitkomst wordt met de oude API-`count(DISTINCT e.id)`-tellers vergeleken door middel van een onafhankelijke `legacy_per_location` op dezelfde `selected`-CTE en binnen dezelfde read-only snapshot. De kleurbeslissing wordt gecontroleerd tegen de werkelijke productiestring en per `id` vergeleken.

Beschikbare fixtures:
- `--emit-overview-phaseb-h2-unfiltered`: nationaal geheel gridLevel8, geen MapFilter; dit dekt historisch H1's 82.463 laadlocaties (aantal kan door nieuwe NDW-snapshot verschillen).
- `--emit-overview-phaseb-h2-ccs2`: dezelfde nationale regio met de echte geparametriseerde `connectorType=ccs2&mode=dc`-MapFilter; dit dekt historisch G1/G2's 1.612 geselecteerde laadlocaties (momentopname kan verschillen).

Elke afzonderlijke test begint `SET ROLE chargewatch_read`; `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`; `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`; precies één `PREPARE/EXECUTE`; en eindigt `ROLLBACK`/`RESET ROLE`. Geen connector-JSON, HTTP, APK of DB-writes.

**Acceptatie voor ELKE H2-fixture**: `compared_rows>0`; `missing_location_ids=0`, `changed_color=0`, `available_count_differences=0`, `total_count_differences=0`, `invalid_new_counts=0`; `zero_zero_locations=correctly_unknown_zero_zero`; `marker_compatibility_matches=t`. Vrije/totale aantallen en locatiekleuren moeten op dezelfde snapshot exact overeenkomen. Bij timeout of afwijking stoppen, geen timeout omhoog zetten. Een **lokaal geslaagde build/selftest en echte NAS-runs zijn nog nodig**; er is geen H2-PASS geclaimd.

#### Windows PowerShell: selftests, beide SQL-fixtures genereren en uploaden

```powershell
& {
    cd C:\GITHUB\ChargeWatch
    git switch feat/map-overview-113-phase-b
    if ($LASTEXITCODE -ne 0) { throw 'Branch wisselen mislukt' }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw 'Pull mislukt' }

    dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --self-test
    if ($LASTEXITCODE -ne 0) { throw 'API-selftests mislukt' }

    $project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
    dotnet build $project --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Probe-build mislukt' }

    foreach ($fixture in @('unfiltered','ccs2')) {
        $switch = "--emit-overview-phaseb-h2-$fixture"
        $lines = dotnet run --no-build --project $project -- $switch
        if ($LASTEXITCODE -ne 0) { throw "H2 SQL-generatie mislukt: $fixture" }
        $sqlText = $lines -join "`n"

        if (-not $sqlText.Contains('P2m-H2: ONE bounded read-only') -or
            ([regex]::Matches($sqlText,'PREPARE cw_phaseb_h2\(').Count -ne 1) -or
            ([regex]::Matches($sqlText,'EXECUTE cw_phaseb_h2\(').Count -ne 1) -or
            -not $sqlText.Contains('SET ROLE chargewatch_read;') -or
            -not $sqlText.Contains('REPEATABLE READ READ ONLY') -or
            -not $sqlText.Contains("statement_timeout = '5s'") -or
            -not $sqlText.Contains('marker_compatibility_matches') -or
            -not $sqlText.Contains('ROLLBACK;') -or
            $sqlText.Contains('EXPLAIN (ANALYZE')) {
            throw "H2 SQL-validatie mislukt: $fixture"
        }

        $file = "p2m-h2-$fixture.sql"
        $sqlText | Set-Content ".\$file" -Encoding utf8
        scp ".\$file" "hinthem@192.168.2.48:~/$file"
        if ($LASTEXITCODE -ne 0) { throw "H2 upload mislukt: $fixture" }
    }
}
```

#### NAS: afzonderlijk uitvoeren, eerst ongefilterd

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -P expanded=on \
  -U chargewatch_owner -d chargewatch < ~/p2m-h2-unfiltered.sql \
  2>&1 | tee ~/p2m-h2-unfiltered-result.txt
```

Wanneer de ongefilterde fixture zonder fouten en zonder afwijkende tellingen slaagt, afzonderlijk de CCS2/DC-fixture:

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -P expanded=on \
  -U chargewatch_owner -d chargewatch < ~/p2m-h2-ccs2.sql \
  2>&1 | tee ~/p2m-h2-ccs2-result.txt
```

**Geen merge/deploy/endpointactivatie** zonder de resultaten te bekijken en los expliciet akkoord. H2 bewijst nog geen HTTP-JSON- of Android-device-integratie; die volgt in de volgende gate.

## P2m-H2 ongefilterd — daadwerkelijke NAS-uitvoering PASS (4 november 2026)

De gebruiker heeft na de goedgekeurde compatibiliteitscorrectie de nieuwe **per-individuele locatie-ID**-pariteitstest `--emit-overview-phaseb-h2-unfiltered` op de NAS uitgevoerd.

| Controleveld | Uitvoer |
|---|---:|
| `compared_rows` | **82.465** |
| `missing_location_ids` | **0** |
| `old_green` / `new_green` | **70.959 / 70.959** |
| `old_red` / `new_red` | **11.497 / 11.497** |
| `old_unknown` / `new_unknown` | **9 / 9** |
| `changed_color` | **0** |
| `available_count_differences` | **0** |
| `total_count_differences` | **0** |
| `zero_zero_locations` / `correctly_unknown_zero_zero` | **9 / 9** |
| `invalid_new_counts` | **0** |
| `marker_compatibility_matches` | **t** |

**Gate H2 ongefilterd PASS.** Op dit gedeelde `REPEATABLE READ READ ONLY`-snapshot komen alle gecontroleerde locatiekleuren én vrije/totale EVSE-tellingen overeen met de bestaande ChargeWatch-API-/Android-regel. De negen locaties zonder operationele EVSE blijven unknown én behouden numeriek `0/0` in de nieuwe singleton-DTO-contractlogica. De eerder gemeten H1-afwijking van 4.661 locaties is hiermee voor de nieuwe ongefilterde testpopulatie verdwenen.

**Niet verkeerd interpreteren:** H1 omvatte eerder 82.463 locaties, H2 nu 82.465; dit zijn verschillende NDW-snapshots, dus het verschil van twee is **geen** selectie- of pariteitsprobleem. H2 meet kleur/count-pariteit per interne `id`, niet HTTP-JSON-parser, Android-uitvoering of performance.

**Nog open:** H2 met `connectorType=ccs2&mode=dc`. **LET OP:** de vorige instructie nam ten onrechte aan dat het CCS2-bestand al op de NAS stond. Dat is niet bevestigd en de gebruiker meldt dat `~/p2m-h2-ccs2.sql` nog niet bestaat. Genereer en upload eerst uitsluitend deze fixture vanaf Windows; controleer op NAS met `ls -lh ~/p2m-h2-ccs2.sql` voordat je de read-only test uitvoert. Voer uitsluitend de afzonderlijke, bestaande H2-CCS2-SELECT uit (5s timeout, `chargewatch_read`, `REPEATABLE READ READ ONLY`):

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -P expanded=on \
  -U chargewatch_owner -d chargewatch < ~/p2m-h2-ccs2.sql \
  2>&1 | tee ~/p2m-h2-ccs2-result.txt
```

Bij timeout/mismatch: stoppen en beoordelen, niet opnieuw uitvoeren met langere timeout. **Geen merge, API-activatie, NAS-deploy, DB-/NDW-wijzigingen.** Draft PR #116 blijft open.

## P2m-H2 CCS2/DC daadwerkelijk PASS — beide markercompatibiliteitsfixtures afgerond (4 november 2026)

Na de expliciet goedgekeurde aanpassing aan `MapOverviewSql` en singleton-`0/0` heeft de gebruiker ook de **CCS2/DC-gefilterde H2** op de NAS uitgevoerd. Werkelijke uitvoer:

| Controle | NAS-uitvoer |
|---|---:|
| `compared_rows` | **1.612** |
| `missing_location_ids` | **0** |
| `old_green` / `new_green` | **1.429 / 1.429** |
| `old_red` / `new_red` | **183 / 183** |
| `old_unknown` / `new_unknown` | **0 / 0** |
| `changed_color` | **0** |
| `available_count_differences` / `total_count_differences` | **0 / 0** |
| `zero_zero_locations` / `correctly_unknown_zero_zero` | **0 / 0** |
| `invalid_new_counts` | **0** |
| `marker_compatibility_matches` | **t** |

**H2 CCS2/DC PASS. H2 ongefilterd was eveneens PASS** (**82.465** locaties, alle kleuren en vrije/totale EVSE-aantallen identiek; negen bekende `0/0` onbekende locaties blijven `0/0`). Daarmee zijn de **beide daadwerkelijke NAS-compatibiliteitsfixtures** afgerond en is de voormalige H1-regressie (4.661 locaties die ten onrechte unknown werden) voor deze tests niet meer aanwezig.

De eerdere P2m-G2-celstatusbenchmark blijft een **historisch PASS voor de oorspronkelijke pre-fix-classifier**, niet de nieuwe norm. Overige open gates: .NET API-selftest-uitvoer nog niet expliciet aangeleverd; nieuwe productie-SQL-plan/runtime na classificatiecorrectie nog niet opnieuw gemeten; HTTP/Npgsql/JSON-respons en Android-integratie; fase C dynamisch <=500 markerobjecten en caching/zoom/coverage. Geen merge, deploy, database-/NDW-wijziging of featureflag-activering.
