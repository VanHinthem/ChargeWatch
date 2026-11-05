# ChargeWatch #113 — Q4c-05b operatorfilterpariteit

**3 november 2026 — voorbereid, nog niet uitgevoerd.**

## Basis: echte productie-operatoren
Q4c-05a leverde in de kleine Oss-bbox `5.50,51.75,5.53,51.77` de volgende waarnemingen op: Vattenfall InCharge **87**, 50five **6**, bp pulse **1**, E-Flux by Road **1**, Laadnet **1**, PowerGo **1**, Qwello Benelux BV **1**. Samen 98 locaties; voor deze operatornamen was `source_values_with_edge_whitespace=0`. Dit is alleen operator-discovery, niet zelf een filterpariteitstest.

## Test
De bestaande [MapFilterQ4Probe](../tools/MapFilterQ4Probe/Program.cs) accepteert nu `--emit-q4c05`. Hij genereert vanuit de echte productie-`MapFilter.TryParse`, `LocationPredicate(5/6)` en `AddParameters` drie onafhankelijke read-only SQL-vergelijkingen:
1. `operator=Vattenfall InCharge` — ongeveer 87 matches verwacht;
2. `operator=Vattenfall InCharge&operator=50five` — ongeveer 93 matches verwacht, OR binnen operatorwaarden;
3. `operator=Vattenfall InCharge&operator=Vattenfall InCharge` — ongeveer 87 matches verwacht, herhaalde querywaarde gededupliceerd door `TryParse`.

De aantallen zijn indicatief en kunnen bij een NDW-import veranderen. Per case geldt **`differing_ids=0`**, `viewport_unique=t`, `overview_unique=t`, `ids_match=t`. Iedere case vergelijkt viewport met de naar exact dezelfde bbox teruggeclipte complete rasterselectie binnen één `REPEATABLE READ READ ONLY` snapshot. De oude limiet van 999 locaties wordt niet geraakt.

## PowerShell op Windows

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c05 |
  Set-Content -Path q4c05-generated.sql -Encoding utf8
Get-Content q4c05-generated.sql -TotalCount 7
scp .\q4c05-generated.sql hinthem@192.168.2.48:~/q4c05-generated.sql
```

## NAS-shell

```bash
cd /volume2/docker/chargewatch
ls -l ~/q4c05-generated.sql
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c05-generated.sql
```

De SQL gebruikt `SET ROLE chargewatch_read`, `BEGIN ... REPEATABLE READ READ ONLY`, `SET LOCAL statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK` en `RESET ROLE` per case. De export vervangt bekende testfilterarrays veilig door SQL-literals voor `psql`; productie-Npgsql-query's blijven geparametriseerd. Er wordt niets geschreven of gedeployed.

**Afbakening:** dit valideert de echte geretourneerde operator-ID-sets voor één, meerdere en herhaalde waarden. Case-sensitiviteit, null/blank en edge-whitespace zijn nog niet onafhankelijk beproefd; de database-output van Q4c-05a bevatte geen edge-whitespace. Q4c-04c (synthetische EVSE-deduplicatie) is nog niet uitgevoerd of bevestigd en blijft open. PR #114 blijft draft.


## Werkelijke NAS-uitvoer — 3 november 2026

De gebruiker voerde de drie gegenereerde operatorvarianten op productie read-only uit:

| Fixture | viewport_ids | grid_candidates | grid_owned | overview_clipped_ids | differing_ids | viewport_unique | overview_unique | ids_match |
|---|---:|---:|---:|---:|---:|---|---|---|
| Vattenfall InCharge | 87 | 337 | 337 | 87 | 0 | t | t | t |
| Vattenfall InCharge + 50five | 93 | 362 | 362 | 93 | 0 | t | t | t |
| Herhaalde Vattenfall InCharge | 87 | 337 | 337 | 87 | 0 | t | t | t |

**Q4c-05b PASS (3/3)** voor deze echte operatorwaarden en kleine Oss-bbox: exacte precluster-ID-setovereenkomst en geen duplicaten. De geobserveerde viewportaantallen bevestigen tevens de eerder uitgevoerde operator-discovery. Case-/whitespacevarianten zijn hiermee nog niet onafhankelijk als grensgevallen getest. Q4c-04c en Q4-06..08 blijven open. Geen mutaties, deploy of merge.
