# ChargeWatch #113 — fase B read-only overview-endpoint: implementatie en validatie

**4 november 2026.** Fase-B-code op `feat/map-overview-113-phase-b`, gebaseerd op fase-A-branch `feat/map-overview-113-phase-a`. **Niet gebouwd/getest in de assistentomgeving** (geen .NET SDK), niet gedeployed en standaard uitgeschakeld.

## Gerealiseerd

- Nieuwe `MapOverviewDatabase.ReadAsync` voert één read-only `REPEATABLE READ`-snapshot uit met bestaande PostgreSQL-`chargewatch_read`-verbinding. Het haalt de bestaande `snapshot_poll_checkpoint.last_observed_at` op en daarna één geparametriseerde overzichtsquery.
- Geografische selectie gebruikt `MapGrid.Cover` over **hele** Web Mercator-rastercellen en `MapFilter.LocationPredicate(6)` uit de **bestaande productieklasse**, met dezelfde `MapFilter.AddParameters` (vijf voorafgaande bbox-/cellSize-parameters). Geen `LIMIT 1000`, geen nieuwe filtersemantiek.
- Aggregatie is eerst per geselecteerde locatie en telt actieve EVSE-rijen zonder connectorjoin; daarna per `(cx,cy)`. `locationCount`, `locationsWithFree`, `locationsWithNoFree` en `locationsUnknown` volgen de **P2i–P2l-gevalideerde benchmarkexpressies**. Een cel van één locatie retourneert de bestaande samengestelde string-`featureId` en daadwerkelijke locatiecoördinaten; clusters hebben stabiel celcentrum en aantallen voor de groen/rood/grijze ring.
- `ApiDatabase.GetMapOverview` en **alleen bij expliciete environment opt-in** `GET /api/v1/map/overview?bbox=...&zoom=...` in `Program.cs`.
- De huidige `GET /api/v1/map/viewport`, 999-begrenzing, connectorcategorieën, locatie-detailroutes en Android blijven **ongewijzigd**.

## Huidige grenzen — belangrijke eerlijkheid over readiness

- `CW_ENABLE_MAP_OVERVIEW` is standaard **niet `true`**, dus de nieuwe route wordt **niet geregistreerd**. De API moet opnieuw worden gestart met `CW_ENABLE_MAP_OVERVIEW=true` om de route gecontroleerd beschikbaar te maken; **nu niet op NAS instellen**.
- `gridLevel=clamp(floor(cameraZoom)+2,0,22)` is een **provisorische** mapping, met nog **geen** automatische resolutieaanpassing. Bij een 501e niet-lege cel volgt expliciet **HTTP 503 `OVERVIEW_BUDGET_EXCEEDED`**, nooit een stilzwijgend afgekapt `complete=true`-resultaat. Fase C neemt het 500-markerbudget en zoom-buckets definitief op.
- Bronversheid gebruikt de ingestelde `CW_API_STALE_AFTER_MINUTES` en `dataVersion=null`; cliënten mogen responses dus **niet als gelijke datasetversie samenvoegen**.
- De availability-klasse bij onbekende status gebruikt de P2-referentie-expressies. **Voor productietoezicht moet een aparte vergelijking met de daadwerkelijk bestaande Android/legacy markerkwaliteit plaatsvinden**, inclusief ontbrekende EVSE's, `REMOVED`/`PLANNED`, EVSE met meer connectoren en onbekende status. P2l bewezen pariteit *tussen de twee benchmarkroutes*, niet automatisch gelijkheid aan bestaande Android-statuskleuring. Daarom blijft dit een afgeschermde prototype-API.
- Een voltooide SQL-query is nog geen garantie dat elke edge-/cornercase in de client correct werkt. SQL-, Npgsql parameter-, API JSON-, integratie- en latency-tests zijn **nog niet uitgevoerd**.
- Fase B gebruikt intern numerieke charging_location IDs voor groepering maar publiceert alleen `country_code-party_id-source_id` als `featureId` voor individuen. Geen locatie-ID-formaatwijziging.
- `ST_Intersects` gebruikt de echte, tot hele cellen verruimde WGS84-bbox; voorkom dat een eerdere uit zoomcamera afkomstige `bbox` de populatie weer afkapt.

## Lokale build en selftest (Windows)

```powershell
cd C:\GITHUB\ChargeWatch
git fetch origin
git switch feat/map-overview-113-phase-b
git pull --ff-only
dotnet build .\src\ChargeWatch.Api\ChargeWatch.Api.csproj --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }
dotnet run --no-build --project .\src\ChargeWatch.Api\ChargeWatch.Api.csproj -- --self-test
if ($LASTEXITCODE -ne 0) { throw 'Selftests mislukt; stop.' }
```

Verwacht minstens `ChargeWatch API selftest passed.` en `Map overview phase A selftest passed.` maar **geen claim op fase-B-PostgreSQL-pariteit**. Stuur de output en eventuele compilerwarnings terug.

## Fase-B-reviewschema vóór activeren op NAS

1. **Lokaal compileren** en bestaande/fase-A-selftests uitvoeren. Geen buildwijzigingen aan de NDW-/Android-app.
2. De nieuwe `MapOverviewDatabase`-SQL door middel van een afzonderlijke read-only NAS-test tegen de bekende P2j/P2l fixtures vergelijken, in één databasesnapshot en met exact dezelfde `MapFilter`-waarden. Harde gates: unieke ID-/celsommen, `free+noFree+unknown=locationCount`, grensgevallen, stringidentiteit, geen impliciete 999-cap.
3. Controleer de classificatie tegen de huidige legacy-/Android-markerregels. **Niet** aannemen dat P2-statusqueries exact de productiecriteria zijn.
4. Pas daarna overweeg opt-in op een niet-publiek testendpoint en HTTP JSON-/responsegrootte-/fouttests. Voor landelijke API UX en automatische grovere gridlevels volgt fase C.
5. Geen API op NAS activeren of deployen zonder uitdrukkelijke goedkeuring. Fase-A-PR #115 en ontwerp-PR #114 blijven draft.

## Openstaande prioriteiten fase C

- Automatische resolutie-retry voor `>500` volledige cellen; efficiënte selectiestrategie zonder onnodige meervoudige nationale volledige scans.
- Zoomovergangen zonder jitter, celbuffer, dataVersion/snapshot-identiteit of veilige no-merge clientcache.
- Gecontroleerde foutcodes/timeouts en NAS server p50/p95.
- Overlapping viewports/Android marker-deduplicatie, exact overlappende locaties.

**Rollback:** oude viewport blijft ongewijzigd, nieuwe previewroute verdwijnt bij verwijderen van opt-in.


## Gebruikersvalidatie — 4 november 2026: fase-B-branch selftests PASS

De gebruiker heeft op Windows na het ophalen van `feat/map-overview-113-phase-b` de volgende console-uitvoer teruggekoppeld:

```text
ChargeWatch API selftest passed.
Map overview phase A selftest passed.
```

**Resultaat: PASS voor bestaande API-selftests en fase-A pure raster-/contractchecks op de fase-B-branch.** Omdat fase B nog geen aparte database-integratietest bevat, bewijst dit **niet** dat `MapOverviewDatabase.ReadAsync` reeds een juiste PostgreSQL-query uitvoert, dat het nieuwe endpoint HTTP/JSON correct afhandelt, of dat de bestaande Android/legacy statusinterpretatie gelijk is aan de benchmark-classificatie. De afzonderlijke buildlog en CI-uitslag zijn niet aangeleverd.

**Volgende gate:** gescheiden read-only NAS-queryvalidatie en gerichte broncode-/semantiekreview vóór inschakelen van de opt-in route. Geen deployment, merge of productie-DB-wijziging.


## P2m voorbereide daadwerkelijke SQL-validatie — 4 november 2026

De querytekst is als **gedeelde productiesource** ondergebracht in `MapOverviewSql.Build(MapFilter)`, aangeroepen door `MapOverviewDatabase.ReadAsync` én rechtstreeks gelinkt in de MapFilterQ4Probe. De read-only NAS-pariteitstest gebruikt dus **exact dezelfde SQL-vorm als het toekomstige endpoint**, niet een handmatig onderhouden tweede query. Zie [MAP_OVERVIEW_113_P2M_SQL_PARITY.md](MAP_OVERVIEW_113_P2M_SQL_PARITY.md) voor de complete Windows-/NAS-opdrachten. P2m vergelijkt voor CCS2/DC landelijk grid8: `id,cx,cy`, 6 rasterkolommen, cellaantallen en statuspartitionering binnen hetzelfde databasesnapshot met P2l's referentie. Dit is een **nog uit te voeren experiment**, geen geclaimde PASS. Geen wijziging aan de deployment- of opt-in-status.

## P2m-F NAS-executie bevestigd — 4 november 2026

De echte geparametriseerde fase-B-SQL is in één begrensde read-only `EXPLAIN ANALYZE` op NAS uitgevoerd: **256,378 ms**, **1.612** geselecteerde locaties en **11** grid8-cellen; connector-first-plan zoals P2m-E. Geen spill, geen 5s-timeout; de planner onderschat geselecteerde locaties (5 versus 1.612). Zie [P2m-runbook](MAP_OVERVIEW_113_P2M_SQL_PARITY.md) voor volledig buffer-/planoverzicht. Dit is **alleen een performance-gate**, geen bewezen ID-/statuspariteit van fase B met P2l of Android. Featureflag uit; geen deploy/merge/DB-wijziging.

## P2m-G1 locatie-ID-/rastercel-pariteit voorbereid — 4 november 2026

Nieuw geïsoleerd testhulpmiddel `OverviewPhaseBGIdParityExporter.cs` (CLI `--emit-overview-phaseb-g1-ids`) op de fase-B-branch. Het vergelijkt uitsluitend de set `(id,cx,cy)` van de P2l-referentie met de echte parametrische `MapOverviewSql.Build(MapFilter)`-selectie, in **één PostgreSQL `REPEATABLE READ READ ONLY`-snapshot** met dezelfde grid8-bounds en CCS2/DC-bindwaarden als P2m-E/F. Geen dubbele per-locatie EVSE-/statusaggregaties; wel bidirectionele EXCEPT, unieke ID's, ongeldige celindices en samenhangende PASS-booleaan. G1 is **nog niet lokaal gebouwd of uitgevoerd**; bij PASS volgt een afzonderlijke G2-status-/rastercountvergelijking. [P2m-G1-runbook](MAP_OVERVIEW_113_P2M_SQL_PARITY.md). **Geen** productiewijziging, DB-migratie, merge of deployment; endpoint opt-in blijft uitgeschakeld.

## P2m-G1 PASS; P2m-G2 status-/gridpariteit voorbereid — 4 november 2026

**Werkelijke NAS-uitvoer G1:** `original_locations=1612`, `original_distinct_ids=1612`, `phaseb_locations=1612`, `phaseb_distinct_ids=1612`, `original_only_id_cells=0`, `phaseb_only_id_cells=0`, `invalid_assignments=0`, `id_cell_parity_matches=t`. Exacte precluster selectie-/cel-ID-pariteit voor de landelijke grid8 CCS2/DC-fixture is daarmee bevestigd. Dit is geen landelijke overall-productiestatusclaim.

**G2 nu voorbereid** in `tools/MapFilterQ4Probe/OverviewPhaseBGGridParityExporter.cs` (`--emit-overview-phaseb-g2-grid`). Eén `PREPARE/EXECUTE` vergelijkt binnen hetzelfde read-only repeatable-read-snapshot beide volledige rasterresultaten (cel-ID, locatieaantal, groen, rood, onbekend). Beide full-grid queries worden elk precies eenmaal materialized uitgevoerd en in twee richtingen met `EXCEPT` vergeleken; dubbele cellen en ongeldige statuspartities zijn extra harde foutcondities. Met dezelfde echte fase-B bindparameters en productie-MapFilter als P2m-F/G1. **Nog niet lokaal gebouwd of op NAS uitgevoerd**; geen G2-PASS claimen. Runbook: [MAP_OVERVIEW_113_P2M_SQL_PARITY.md](MAP_OVERVIEW_113_P2M_SQL_PARITY.md). Geen productiecode-/DB-schema-/NDW-wijziging, API-activering, merge of deployment.

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

## P2m-H1 markersemantiek-audit voorbereid — 4 november 2026

Na de **G2-PASS** (11 identieke rastercellen, 1.612 identieke locaties, nul afwijkende zes-koloms statusrijen) is de volgende gate een controle van de *huidige* ChargeWatch-Android-markers tegen de nieuwe fase-B-berekening. De code-audit laat zien dat `GetMapViewport` numerieke `availableCount`/`totalCount` produceert, door `ChargeWatchMapClient.kt` als veilige aantallen gelezen; `AvailabilitySummary.status` maakt groen bij vrij>0, rood bij totaal>0 zonder vrij, en anders onbekend. Fase-B `unclassified_evses>0` kan een anders groene/rode locatie onbekend maken en bij onbekende singleton beide aantallen `null` retourneren. De huidige Android-klant tekent bij oude `0/0` een onbekende kleur met label `0/0`; toekomstige null-rendering moet nog worden vastgesteld.

Nieuwe **uitsluitend diagnostische** exporter `OverviewPhaseBHMarkerSemanticsExporter.cs`, argument `--emit-overview-phaseb-h1-marker-audit`: nationale ongefilterde, werkelijke fase-B-locatieselectie, eenmalige aggregatie van actieve EVSE-statussen, oude en nieuwe count-/kleurinterpretatie en oorzaaktellingen, read-only snapshot met 5s-timeout. Bij uitvoering aandacht voor groen→onbekend, rood→onbekend, tellerafwijkingen door trimmen, ontbrekende/onjuiste statussen, en de mogelijke `0/0`-labelwijziging. **Nog niet lokaal gebouwd of op NAS uitgevoerd; geen H1-PASS of semantiekbesluit.** [P2m-H1-runbook](MAP_OVERVIEW_113_P2M_SQL_PARITY.md). Productie-endpoint en Android ongemoeid gelaten, featureflag uit.

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

## Goedgekeurde H1-markercompatibiliteitscorrectie geïmplementeerd — 4 november 2026

Na de landelijke H1-audit (**82.463** locaties, **4.661** onbedoelde grijs/unknown markers: **1.300** voorheen groen en **3.361** voorheen rood; **nul EVSE-countverschillen**) heeft de gebruiker expliciet ingestemd met het behoud van bestaande ChargeWatch Android-markersemantiek en `0/0`-presentatie.

**Gewijzigde code op alleen de fase-B-branch:**

- `src/ChargeWatch.Api/MapOverviewSql.cs`: `locations_with_free` nu uitsluitend `free_evses>0`, `locations_with_no_free` nu `free_evses=0 AND total_evses>0`, `locations_unknown` uitsluitend `total_evses=0`. Het extra `unclassified_evses`-aggregaat en de brede unknown-override zijn verwijderd. `free_evses` gebruikt voortaan dezelfde exacte `upper(e.status)='AVAILABLE'`-voorwaarde als de huidige viewport; `total_evses` en alle MapFilter-gebruik blijven gelijk.
- `src/ChargeWatch.Api/MapOverviewContracts.cs`: `MapOverviewLocationMarker.FromCounts` bewaart **numerieke** `availableCount=0,totalCount=0` met `CountQuality="unknown"` en valideert alle numerieke tellers.
- `src/ChargeWatch.Api/MapOverviewDatabase.cs`: singletonmarkers gaan via `FromCounts`, niet langer via `null` bij unknown; de bestaande externe OCPI-featureId-string blijft ongewijzigd.
- `src/ChargeWatch.Api/MapOverviewPhaseBCompatibilitySelfTest.cs` plus `Program.cs`: nieuwe selftest voor predicates, `0/0` in de echte JSON-serialisatie, known/red tellers en ongeldige `available>total`.
- `tools/MapFilterQ4Probe/OverviewPhaseBH2CompatibilityExporter.cs` plus CLI-opties voor **ongefilterde** en **CCS2/DC-gefilterde** landelijke `READ ONLY REPEATABLE READ`-pariteit per exacte locatie-ID tegen de originele ChargeWatch-API-telregels. H1 en G2 zijn duidelijk als *historische pre-fix*-referenties gemarkeerd.

**Niet gebeurd:** productie-API geïmplementeerd op main, Android-gedrag veranderd, database/index/NDW gemuteerd, endpoint aangezet, NAS gedeployed, branches gemerged. **Nog niet lokaal gebouwd of op NAS opnieuw gevalideerd**; niet als regressie-PASS markeren. De oude P2m-G2-status-PASS gold voor de vóór H1 geldende P2l-classificatie; na de bewuste statuscorrectie mogen P2l-vs-fase-B-statusverschillen juist verwacht worden. Gebruik nieuwe H2-gates in [P2m-runbook](MAP_OVERVIEW_113_P2M_SQL_PARITY.md). Na H2-PASS volgen HTTP/Npgsql, Android markerweergave, budget en cache/zoom in aparte stappen.

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

**Nog open:** H2 met `connectorType=ccs2&mode=dc`; de twee SQL-bestanden werden in de voorgaande Windows-generatiestap geüpload. Voer uitsluitend de afzonderlijke, bestaande H2-CCS2-SELECT uit (5s timeout, `chargewatch_read`, `REPEATABLE READ READ ONLY`):

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

## P2m-F2 werkelijk uitgevoerd — gecorrigeerde SQL-performance PASS (4 november 2026)

Gebruiker heeft de **nieuwe post-fix productiequery** via de bestaande `--emit-overview-phaseb-execution`-exporter op de NAS gedraaid als één `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, SUMMARY) EXECUTE` binnen `REPEATABLE READ READ ONLY` en `statement_timeout='5s'`. Geen fouten/timeout.

| Metriek | Uitvoer |
|---|---:|
| `Execution Time` | **281,785 ms** |
| `Planning Time` | **21,979 ms** |
| Historische pre-fix uitvoering F | **256,378 ms** |
| Verschil t.o.v. F | **+25,407 ms (+9,91%)** |
| Geselecteerde locaties | **1.612** |
| Rastercellen (`GroupAggregate` rows) | **11** |
| Actieve filterconnectoren na scan (`Gather` rows) | **6.486** |
| Unieke connector-`evse_id` na aggregatie | **6.479** |
| Geschikte EVSE/location-gefilterde kandidaten (`location_id`) | **1.640** |
| `selected` na actieve/geopositioneerde locatie en grid-bbox | **1.612** |
| `per_location` joined EVSE-rijen | **9.341** |
| Shared buffers | **36.354 hit / 33.246 read** |
| Shared buffers dirtied / written (door leesquery toegestaan op paginaniveau) | **905 / 4.682** |
| Sort `per_location` | **quicksort, 1.394 kB** |
| Sort `grid` | **quicksort, 184 kB** |
| HashAggregate voor 6.479 EVSE-ID's | **433 kB, 1 batch** |
| HashAggregate voor 1.640 locatie-ID's | **145 kB, 1 batch** |

**Gate F2: PASS** onder de ingestelde 5s-executiebewaker, zelfde CCS2/DC-grid8-fixture en geen disk spills of fout. Het plan blijft overwegend connector-first: parallelle sequentiële scan van `connector` met 70.798 door filter afgewezen rijen per parallel worker-weergave (`Rows Removed by Filter` gerapporteerd 70.798; PostgreSQL presenteert loops-gemiddelden), daarna EVSE-PK-indexzoekacties en charging_location-PK-lookup, vervolgens per-locatie/statusaggregatie en 11 cellen. De zeer vele `shared read` buffers (~260 MiB aan pagina's bij standaard 8kB) en backend buffer-writes vragen **later** om meerdere metingen/concurrentiecontrole, maar de testcase is niet dicht bij de 5s grens. Het verschil van circa 10% met F bewijst **geen** regressie zonder vergelijkbare cache/I/O-belasting en herhaalde metingen.

PostgreSQL `Buffers: dirtied/written` bij een SELECT geven géén bewijs van applicatie-DML: page hint-bit updates en uitgeschreven gedeelde buffers zijn mogelijk terwijl de database-transactie `READ ONLY` is. Deze proef verandert geen businessrecords, schema, index of NDW.

**Open gates:** API `--self-test` incl. uitgebreide volledige response-JSON is nog niet door gebruiker bevestigd met uitvoer; daadwerkelijk `HTTP/Npgsql/JSON`-request naar de preview-handler is nog niet getest en vereist een apart gecontroleerd testtraject. Ook fase-C-budget/zoom/cache, Android-clusterrenderer en echte p95/concurrentie blijven open. **H2 ongefilterd en H2 CCS2/DC zijn reeds PASS**. Geen merge/deploy/featureflag-activering; draft PR #116 blijft open.

## API/JSON-selftest PASS; lokale HTTP/Npgsql-gate I1 volgt — 4 november 2026

De gebruiker bevestigde exact `Map overview phase A selftest passed.` en `Map overview phase B compatibility selftest passed.` op Windows met de bijgewerkte fase-B-code. Die tweede test omvat ook de volledige polymorfe `MapOverviewResponse`-JSON. **Offline API/JSON-gate PASS.** De twee H2-pariteitstests en F2-performance (**281,785 ms, 1.612 locaties / 11 rastercellen**) zijn eerder PASS.

De echte HTTP/Npgsql-respons is nog **niet** getest: voor I1 een tijdelijk alleen op Windows-loopback gebonden API-proces voorzien, gekoppeld via SSH-tunnel naar de NAS-loopback PostgreSQL-poort als **chargewatch_read**, met previewfeatureflag uitsluitend in dat lokale proces. Procedure staat in [P2m-F2/I1-runbook](MAP_OVERVIEW_113_P2M_F2_POSTFIX_PERFORMANCE.md). Geen productiedienst of productievlag veranderen, geen NAS-deployment/merge, geen schema/NDW-wijziging. Fase C objectbudget/zoom/cache en Android blijven open.
