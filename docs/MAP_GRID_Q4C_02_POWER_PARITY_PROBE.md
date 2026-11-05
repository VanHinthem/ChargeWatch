# ChargeWatch #113 — Q4c-02 filterpariteit: vier vermogensbanden

**3 november 2026 — voorbereid; nog niet uitgevoerd.** Dezelfde standalone .NET 10 testharness die Q4c-01 heeft gegenereerd kan nu `--emit-q4c02` uitvoeren. Er zijn geen API-/Android- of database-schemawijzigingen.

## Doel

Vergelijk daadwerkelijke locatie-ID-sets tussen de oude viewportselectie en de volledige rasterselectie die naar exact dezelfde kleine Oss-viewport wordt teruggeclipt. Vier onafhankelijke gevallen: `power=lt50`, `gte50`, `gte150`, `gte300`.

Iedere selectie gebruikt de echte `MapFilter.LocationPredicate(5)` respectievelijk `LocationPredicate(6)`; de testexport rendert alleen vaste, door `MapFilter.TryParse` gevalideerde testwaarden als SQL, zodat PostgreSQL op de NAS kan uitvoeren zonder .NET of API-deployment. Voor vermogen bevat het gegenereerde predicate rechtstreeks het bestaande `MapFilter.EffectiveWattsSql`. Elke band draait in een afzonderlijke `REPEATABLE READ READ ONLY`-transactie; de twee selectie-CTE's binnen één band gebruiken dus hetzelfde snapshot. Verschillende banden hoeven niet hetzelfde snapshot te hebben.

## Lokaal — PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c02 |
  Set-Content -Path q4c02-generated.sql -Encoding utf8
Get-Content q4c02-generated.sql -TotalCount 8
scp .\q4c02-generated.sql hinthem@192.168.2.48:~/q4c02-generated.sql
```

De SSH-home-directory was in de vorige sessie niet noodzakelijk gelijk aan de Compose-map. Controleer op de NAS eerst `pwd` en `ls -l ~/q4c02-generated.sql`; voer bij voorkeur de file op dat exacte pad uit.

## NAS — read-only

```bash
cd /volume2/docker/chargewatch
ls -l ~/q4c02-generated.sql
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c02-generated.sql
```

Als het bestand in plaats daarvan al in de Compose-map ligt, gebruik `< q4c02-generated.sql`. Niet overschrijven of wijzigen in de database. De SQL begint met `SET ROLE chargewatch_read`, bevat `BEGIN ... REPEATABLE READ READ ONLY`, `SET LOCAL statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, en sluit af met `ROLLBACK; RESET ROLE;` per testcase.

## Acceptatie

Voor **iedere** vermogensband: `differing_ids=0`, `viewport_unique=t`, `overview_unique=t`, `ids_match=t`. Leg ook de aantallen vast om te beoordelen of de test inhoudelijk representatief is. Lege ID-sets zijn geen voldoende positief bewijs; selecteer zo nodig later een passend gebied. De volledige rasterkandidaten mogen groter zijn dan de teruggeclipte viewport.

**Vervolgvalidatie na deze run:** aparte bewijsvoering voor exact 50/150/300 kW-grenzen en voor `max_electric_power IS NULL` waarbij `EffectiveWattsSql` een berekend vermogen gebruikt. De huidige live vergelijking bewijst alleen dat beide selecties hetzelfde centrale predicate evalueren; daarmee bewijst ze niet onafhankelijk dat de powerberekening juist is. Ook Q4-03..08 en Q5 blijven open. PR #114 blijft draft.


## Werkelijke NAS-resultaten — 3 november 2026

De gebruiker heeft de vier door de bestaande `MapFilter` gegenereerde SQL-vergelijkingen daadwerkelijk read-only uitgevoerd:

| Fixture | viewport_ids | grid_candidates | grid_owned | overview_clipped_ids | differing_ids | viewport_unique | overview_unique | ids_match |
|---|---:|---:|---:|---:|---:|---|---|---|
| lt50 | 97 | 412 | 412 | 97 | 0 | t | t | t |
| gte50 | 1 | 10 | 10 | 1 | 0 | t | t | t |
| gte150 | 1 | 8 | 8 | 1 | 0 | t | t | t |
| gte300 | 0 | 4 | 4 | 0 | 0 | t | t | t |

**Q4c-02 PASS voor ID-setpariteit in alle vier scenario's**. De `gte300`-vergelijking in de kleine viewport betreft twee lege sets (0/0) en is dus geen positieve selectietest, hoewel er vier grid-owned matches buiten de kleine viewport gevonden zijn. Een afzonderlijke grenswaarde-/berekende-vermogentest is nodig om te bewijzen dat `EffectiveWattsSql` zelf de bedoelde kW-waarden oplevert. Geen databasewrites, migratie, merge of deployment.
