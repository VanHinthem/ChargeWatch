# ChargeWatch #113 — Q4c-08 locatiebrede beschikbaarheidsring

**3 november 2026. Voorbereid; buildfout in eerste export gerepareerd, nieuwe versie nog niet lokaal gebouwd of op NAS uitgevoerd.** Q4c-07 (lege selectie) is geslaagd. Q5 bronkwaliteitsbesluit staat nog open.

## Doel en bewijsgrens

Selecteer met de bestaande `MapFilter.LocationPredicate(5)` de locaties die een operationele CCS2/DC-connector hebben binnen de bestaande kleine Oss-bbox. Vergelijk voor **dezelfde geselecteerde locaties**:

- `matching_operational`: aantal unieke operationele EVSE's met ten minste één bij het technische filter passende connector, geteld per EVSE via `EXISTS`.
- `all_operational`: aantal **alle** operationele EVSE's op deze geselecteerde locatie, ongeacht connectorcategorie.
- `all_free` en `all_unclassified`: tellingen over alle actieve operationele EVSE's, niet alleen over passende technische connectoren.
- Voorlopige ringklasse groen/rood/onbekend, waarvan per geselecteerde locatie exact één categorie wordt bepaald.

**Bewijsdoel:** `all_operational >= matching_operational` voor elke rij. Een rij met `has_other_evses=t` geeft een positief praktijkvoorbeeld waarin de ringinformatie breder moet zijn dan het technische filter. De samenvatting moet voldoen aan `selected_locations = green_locations+red_locations+unknown_locations` en `partition_ok=t`. Als er geen `has_other_evses=t` voorkomt, blijft een aparte positieve fixture nodig.

**Belangrijke beperking:** deze Q4c-08 is een read-only SQL-audit van het voorgestelde *locatiebrede* rekenpad, niet een daadwerkelijk endpoint (die bestaat nog niet). De tijdelijke statusclassificatie houdt geen definitieve rekening met waarnemingsversheid en bronkwaliteit: bij Q5 moet onbekend/stale conservatief worden geclassificeerd. De ringkleur mag dus nog **niet** als productiecorrect worden goedgekeurd. De filterselectie wordt wel vanuit de echte productie-`MapFilter` gegenereerd. Deze proef is ook geen onafhankelijk bewijs voor de technische `MapFilter`-selectiesemantiek; die is eerder via ID-setvergelijkingen gevalideerd.

## Windows PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt: geen SQL exporteren.' }
$sql = dotnet run --no-build --project $project -- --emit-q4c08
if ($LASTEXITCODE -ne 0 -or -not ($sql -match '^-- Q4c-08:')) { throw 'Geen geldige Q4c-08 SQL uitvoer.' }
$sql | Set-Content -Path q4c08-generated.sql -Encoding utf8
Get-Content q4c08-generated.sql -TotalCount 7
scp .\q4c08-generated.sql hinthem@192.168.2.48:~/q4c08-generated.sql
```

## NAS-shell

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c08-generated.sql
```

Twee resultsets: detail per geselecteerde locatie, en de categorie-partitietelling. SQL gebruikt `SET ROLE chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK`. Bij timeout eerst de query herbeoordelen, niet zomaar de limiet verhogen.

## Nog open na deze stap

- Q4c-04c synthetische multi-connector-deduplicatie is voorbereid maar nog niet door gebruiker als uitgevoerd gerapporteerd.
- Onafhankelijke cross-connector-AND/OR fixture, indien nodig.
- Q5 betrouwbare beschikbaarheidsclassificatie, staleness en bronstatus.
- End-to-end overzichtsendpoint, `coverage.complete` en cachegedrag pas in latere implementatiefase.

Geen productiecode-/schemamutaties, NAS-deploy of merge. PR #114 blijft draft.


## Correctie eerste export — 3 november 2026

De gebruiker rapporteerde `invalid command \\GITHUB` bij het uitvoeren van een SQL-bestand dat in werkelijkheid een C# compilerfout bevatte: `CS9006` op `Q4cSqlExporter.cs:108`. Oorzaak: de nieuwe methode had `return $"""` in plaats van `return $$"""` terwijl de tekst `{{predicate}}` bevat. Dit is in commit `ebfdcfa` gecorrigeerd. Het bestand met de foutmelding mag **niet** als SQL gebruikt worden; opnieuw genereren na een expliciet geslaagde build, en controleren of de eerste regel met `-- Q4c-08:` begint. Deze correctie is nog **niet** met een lokale dotnet-build of NAS-uitvoering bevestigd. Het mislukte `psql`-commando stopte bij de eerste fout; geen succesvolle SQL-uitvoering is gerapporteerd.


### Aanvullende correctie — 3 november 2026

Tweede gebruikersbuild meldde opnieuw `CS9006` en `dotnet run --no-build` startte daarna een oude binary zonder `--emit-q4c08`. Controle van de GitHub-bestandinhoud toonde dat de eerdere commit de raw-stringdelimiter niet daadwerkelijk had gewijzigd. **Nieuwe correctie commit `5ed4937`: geen geïnterpoleerde raw-string meer; de SQL staat in `return """..."""` met twee expliciete, identieke plaatsvervangers die met `.Replace("__Q4C08_LOCATION_PREDICATE__", predicate, StringComparison.Ordinal)` worden ingevoegd.** De remote broncode is gecontroleerd op twee query-plaatsvervangers. Een nieuwe lokale build en NAS-executie zijn nog niet aangetoond. `--no-build` pas gebruiken na een geslaagde `dotnet build`; bij een buildfout niets exporteren en ook geen eerdere SQL-file uitvoeren. Controleer tevens `git branch --show-current` en `git rev-parse --short HEAD`, aangezien een `git pull` op een andere branch de ontwerpcode niet binnenhaalt.


## Werkelijke NAS-resultaten — 3 november 2026

De gebruiker heeft de gecorrigeerde Q4c-08 SQL succesvol uitgevoerd. Daarmee is bevestigd dat de generator uitvoerbare SQL produceert; niet afzonderlijk vastgelegd is welke exacte `dotnet build`-uitvoer hij zag.

| id | feature_id | matching_operational | all_operational | all_free | all_unclassified | provisional_ring_status | scope_ok | has_other_evses |
|---:|---|---:|---:|---:|---:|---|---|---|
| 81262 | NL-BPE-6d278e19-8278-4a1d-9609-45025fca3a8a | 2 | 2 | 2 | 0 | green | t | f |
| 85663 | NL-PFG-23610 | 2 | 2 | 1 | 0 | green | t | f |

Aggregaat: `selected_locations=2`, `green_locations=2`, `red_locations=0`, `unknown_locations=0`, `partition_ok=t`.

**Q4c-08 basisinvarianten PASS**: twee werkelijk geselecteerde locaties, per locatie `all_operational >= matching_operational`, en de voorlopige ringklassen vormen een complete partitie. **De cruciale positieve scopecase ontbreekt nog**, omdat `has_other_evses=f` op beide locaties. Daarom nog geen volledige Q4-08-goedkeuring. Een read-only synthetische fixture moet een technisch geselecteerde locatie met niet-passende overige EVSE's testen, inclusief kleurverschillen tussen gefilterd en locatiebreed. De voorlopige kleur is bovendien niet Q5-gevalideerd voor actualiteit en bronkwaliteit. Geen mutaties/deployment/merge.
