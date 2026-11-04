# ChargeWatch #113 — P2m-F2 post-fix performance en JSON-contract

**4 november 2026 — voorbereid, geen F2 PASS-claim.**

Na H2-ongefilterd PASS (82.465 locaties) en H2-CCS2/DC PASS (1.612 locaties) is de nieuwe locatiekleur- en EVSE-tellersemantiek voor beide fixtures exact gelijk aan de bestaande Android-kaart. De oude SQL-performancemeting **256,378 ms** gold echter voor de classifier **vóór** de correctie.

## F2: hergebruik bestaande productiequery-exporter

De al aanwezige CLI-optie **--emit-overview-phaseb-execution** gebruikt de werkelijk actuele MapOverviewSql.Build met MapFilter CCS2/DC en volledige grid8-landelijke cellen. De exporter wordt via OverviewPhaseBExecutionExporter en OverviewPhaseBPreparedPlanExporter gebouwd en maakt één echte PostgreSQL PREPARE + EXPLAIN (ANALYZE, BUFFERS, SETTINGS, SUMMARY) EXECUTE. De productie-SQL wordt niet gedupliceerd.

Lokaal op Windows: wissel naar feat/map-overview-113-phase-b, haal de laatste commits op, voer API --self-test uit, bouw MapFilterQ4Probe en exporteer de huidige SQL via bovenstaande switch. Valideer dat de SQL de *nieuwe* free>0/red>0/unknown=0-classificatie en geen unclassified_evses bevat. Sla op als **p2m-f2-postfix-ccs2.sql**, upload met SCP naar de NAS en verifieer dat het bestand daar daadwerkelijk aanwezig is.

NAS-probe: vanuit /volume2/docker/chargewatch, voer het SQL-bestand met docker compose exec -T db psql -X -v ON_ERROR_STOP=1 -U chargewatch_owner -d chargewatch uit. Leg alle output vast in **p2m-f2-postfix-ccs2-result.txt**. De exporter gebruikt SET ROLE chargewatch_read, REPEATABLE READ READ ONLY, 5s statement timeout, 1s lock timeout, jit off, ROLLBACK en RESET ROLE. Stop bij timeout, geen automatische retries of hogere limieten.

Analyseer Execution Time, plan-vs-actual rows, joinvolgorde, buffers en eventuele spills. Eenzelfde fixture maakt vergelijken met historische F mogelijk, maar twee afzonderlijke snapshots kunnen andere locatietellingen hebben. Eén run is geen p95/HTTP-SLA.

## Nieuwe offline full-response JSON-selftest

MapOverviewPhaseBCompatibilitySelfTest is uitgebreid met serialization van een complete MapOverviewResponse: polymorfe cluster/location markers, numerieke 0/0-singleton met countQuality unknown, 1/2 met known, OCPI-featureId, complete coverage, stale-flag, dataVersion=null en correct gedifferentieerde locatiecategorieën. Deze check draait mee binnen **dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --self-test**.

De offline selftest is nog niet aantoonbaar op Windows uitgevoerd. De assistentomgeving beschikt niet over .NET. De test is géén daadwerkelijk HTTP/Npgsql request. Die volgt gecontroleerd na F2 en vereist een aparte omgeving en autorisatie; de productievlag CW_ENABLE_MAP_OVERVIEW blijft uit.

**Geen merge/deployment, geen database-/NDW-/Android-wijzigingen, geen API-activatie. Draft PR #116 blijft open.**

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

## API/JSON-selftest bevestigd PASS — 4 november 2026

De gebruiker heeft de daadwerkelijke Windows-uitvoer gedeeld van de op de actuele fase-B-branch aangesloten `dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --self-test`:

```text
Map overview phase A selftest passed.
Map overview phase B compatibility selftest passed.
```

**Gate pure C# / offline JSON: PASS.** Het fase-B-selftestpad controleert exacte productie-SQL-compatibiliteitspredicaten en serialiseert een **volledige `MapOverviewResponse`** met beide polymorfe markertypen `cluster`/`location`, volledige `coverage`, nullable `dataVersion`, stale flag, vaste samengestelde OCPI `featureId`, bekende `1/2` en onbekende numerieke `0/0`-tellers.

De reeds vastgelegde **H2 ongefilterd, H2 CCS2/DC en F2** blijven PASS. **Nog NIET getest:** echte verbinding vanaf de HTTP-API via Npgsql naar PostgreSQL en de door ASP.NET uitgegeven JSON-response. Die vereist apart gecontroleerde lokale testhost met eigen read-only databasecredential; geen NAS-productiedeployment/flagwisseling. Fase C max 500 markers, cache, zoom en Android-client blijven open.

## P2m-I1: geïsoleerde lokale HTTP/Npgsql-proef voorbereid (na selftest PASS)

De API-selftests zijn **werkelijk PASS**, maar een echte HTTP-response via `ApiDatabase.GetMapOverview` en Npgsql is nog **niet** getest. Een veilig traject zonder NAS-productie-API-activatie:

1. **Windows terminal A:** open uitsluitend een lokale SSH-forward naar NAS-loopback PostgreSQL (`docker-compose.yml` publiceert op de NAS `127.0.0.1:5432`): `ssh -o ExitOnForwardFailure=yes -N -L 127.0.0.1:15432:127.0.0.1:5432 hinthem@192.168.2.48`. Open geen NAS-LAN-/publieke poort.
2. **Windows terminal B:** start de actuele API vanuit de fase-B-branch in een apart **lokaal proces** met `ASPNETCORE_URLS=http://127.0.0.1:5089`, `CW_ENABLE_MAP_OVERVIEW=true` uitsluitend in deze lokale procesomgeving, en `CW_API_CONNECTION` naar `Host=127.0.0.1;Port=15432;Database=chargewatch;Username=chargewatch_read` plus afgeschermde lokaal ingevoerde read-role-credential. Geen owner-/import-password als workaround. De bestaande `AssertReadOnlyRoleAsync` weigert onjuiste privileges. `dotnet run --no-launch-profile --project src/ChargeWatch.Api/ChargeWatch.Api.csproj`.
3. **Windows terminal C:** `GET http://127.0.0.1:5089/api/v1/map/overview?bbox=3.0,50.6,7.4,53.8&zoom=6&connectorType=ccs2&mode=dc`. Verifieer HTTP 200, filter-versie-header=1, `schemaVersion=1`, `gridLevel=8`, `coverage.complete=true`, `dataVersion=null`, 1..500 markers en per cluster `locationsWithFree+locationsWithNoFree+locationsUnknown=locationCount`. Voor elke singleton een OCPI-string-`featureId` en numerieke `availableCount/totalCount`, ook `0/0`. Test daarnaast een kleine Oss-bbox op hogere zoom voor echte losse markers en een ongeldige `zoom=23` voor HTTP 400.
4. Stop de lokale host en SSH-tunnel, verwijder proces-omgevingvariabelen met credentials. Log of deel nooit databasewachtwoorden.

De camerabbox en de SQL-grid8-fixture hoeven niet op exact dezelfde cellen te eindigen; **verwacht dus niet hardcoded 1.612 locaties of 11 cellen voor het HTTP-request**. Geen HTTPS-redirect naar publieke productiehost toestaan; bind alleen aan 127.0.0.1. De huidige fase B geeft bij overschrijding van 500 objecten bewust 503; dynamische coarsening is fase C.

**Niet uitgevoerd:** I1 HTTP/Npgsql-respons. **Niet gewijzigd:** NAS-productie-API/featureflag, deployment, PostgreSQL-schema, NDW of Android.

## I1-blokkade bevestigd — SSH-forwarding uit; geen host-DB-aannames (4 november 2026)

Tijdens de tijdelijke lokale Windows API-start gaf Npgsql een transport-reset bij `ApiDatabase.AssertReadOnlyRoleAsync`; de API heeft **niet** op Windows-poort 5089 geluisterd en er is geen HTTP-response geproduceerd. De user leverde onderstaande feitelijke observaties:

- Windows TCP `127.0.0.1:15432`: bereikbaar als **lokale SSH-listener**; `127.0.0.1:5089`: niet bereikbaar (API startte niet).
- SSH-server meldt `channel 1: open failed: administratively prohibited: open failed`.
- `sshd -T` op NAS toont **`allowtcpforwarding no`**, `disableforwarding no`, `permitopen any`. De SSH-beheerpolicy verhindert de vereiste forward; dit **niet** stilzwijgend uitschakelen.
- `docker compose ps db` op NAS toont **`5432/tcp`** (geen gepubliceerde hostpoort). `ss` toont afzonderlijk `postgres` op host `127.0.0.1:5432`, maar dat **bewijst niet** dat dit de ChargeWatch-containerdatabase is. Niet verder proberen via de eerder veronderstelde host-tunnel.
- `deploy/nas/compose.yaml` laat de productie-API via de **interne Docker Compose-netwerkhost `db:5432`** en `chargewatch_read` verbinden. Die topologie is de juiste referentie; NAS-productie-API blijft uit de gewijzigde previewbranch.

**Status:** I1 lokale Windows SSH-tunnelvariant is **GEBLOKKEERD, niet PASS**; de eerder beschreven Windows-forwardprocedure in dit document is hiermee **vervallen** en mag niet zonder expliciet nieuwe SSH-beleidsautorisatie worden herhaald.

**Veilig alternatief voor review (nog NIET gedeployed):** apart, tijdelijk gebouwde fase-B-API-testinstantie op hetzelfde *interne* Docker-netwerk als `chargewatch-db-1`, met read-only `chargewatch_read`-credential, een **afzonderlijke uitsluitend loopback-gebonden poort**, zonder Compose-prod-API/collector/db te herstarten of hun environment/volumes te wijzigen. Bepaal eerst NAS-runtime-/Docker-netwerkcontext read-only; vooraf apart expliciet akkoord voor een tijdelijke NAS-testcontainer. Maak geen nieuwe host-PostgreSQL-publicatie en schakel `AllowTcpForwarding` niet alsnog in als workaround. Na gebruik testcontainer verwijderen zonder Docker-volumes te raken; geen image-tag wijziging voor bestaande diensten.

**Uitsluitend read-only inventarisatie (NAS, geen secrets printen):**
```bash
cd /volume2/docker/chargewatch
sudo docker compose ps api db
sudo docker compose config --services
sudo docker inspect chargewatch-db-1 --format '{{range $name, $network := .NetworkSettings.Networks}}{{println $name}}{{end}}'
uname -m
```
Indien het UGREEN-project via een andere compose-file draait, eerst de feitelijke Docker-container/netwerknaam vaststellen voordat verdere stappen worden voorbereid. **H2 beide PASS, F2 PASS, API/JSON-selftests PASS blijven ongewijzigd**; HTTP/Npgsql I1 blijft open.

## I1 NAS runtime-inventarisatie bevestigd — veilige testopzet (4 november 2026)

Gebruiker leverde de actuele productieomgeving uit uitsluitend read-only commando's:

| Aspect | Uitvoer |
|---|---|
| Productie-API | `chargewatch-api-1`, image `ghcr.io/vanhinthem/chargewatch-api:sha-473e64c5de29e5f78371260be480a210d30f02b9`, status Up, **`192.168.2.48:5088->8080/tcp`** |
| PostgreSQL | `chargewatch-db-1`, zelfde release-SHA, Up (healthy), **`5432/tcp`** (uitsluitend container, geen host-publicatie) |
| Compose services | `db`, `db-upgrade`, `api`, `collector` |
| Intern Docker-netwerk | **`chargewatch_default`** |
| Hostarchitectuur | **`x86_64`** |
| SSH-forwarding | `sshd -T: allowtcpforwarding no`; forwarding geblokkeerd, niet wijzigen |

**Voorgestelde I1-route, nog geen uitvoeringsautorisatie:** bouw op Windows met de .NET 10 SDK uitsluitend de actuele fase-B-API als portable framework-dependent Release `dotnet publish` met `UseAppHost=false`. Kopieer deze gepubliceerde DLL/dependencybestanden met SCP naar een **nieuwe tijdelijke directory** in de home-directory op de NAS (geen productiecode/Compose overwrite). Start **na apart akkoord** één eenmalige `docker compose run --no-deps --rm` op service `api` met `-v <tijdelijke-publicatiemap>:/app:ro`, `-e CW_ENABLE_MAP_OVERVIEW=true` uitsluitend in die **testcontainer**, en `-p 127.0.0.1:5090:8080`; geen `--service-ports` en dus geen overname van de productiepoort `5088`. De container gebruikt automatisch het bestaande `api`-Compose-connectionstringconfig met `chargewatch_read` en Docker-host `db` in het interne `chargewatch_default`-netwerk. Test HTTP uitsluitend via `curl` vanaf NAS-loopback `127.0.0.1:5090` (vanuit Windows is die loopback niet rechtstreeks bereikbaar en SSH-forwarding staat uit). Stop via Ctrl+C; door `--rm` is de testcontainer eenmalig. Nooit `compose up`, `redeploy`, `--service-ports`, `down`, `down -v` of productietag-/credentialswijzigingen uitvoeren.

**Vooraf te verifiëren vóór uitvoering:** bestaande API-image kan als runtime met portable .NET 10 publish output worden gebruikt; staging-directory heeft voor de niet-root containergebruiker lees-/traverserechten; hostpoort `127.0.0.1:5090` is vrij; containercommand gebruikt alleen `--no-deps`. Credentials mogen niet via logs/CLI worden uitgeprint. Geen aparte Docker-images bouwen/uploaden, geen NAS-sourcecheckout en geen database/schema/migratieacties.

**Acceptatie:** API start met succesvolle `AssertReadOnlyRoleAsync` via `db`; één loopback HTTP-GET van CCS2/DC-overview levert geldige polymorfe JSON, geen 503; een kleine kaartscope en een 400-input testen daarna apart. H2/H2/F2/offline selftests zijn reeds PASS. HTTP-I1 is nog **NIET** uitgevoerd. Geen verandering aan productie-API `chargewatch-api-1`, `CW_ENABLE_MAP_OVERVIEW` van die container, reverse proxy of collector/DB.
