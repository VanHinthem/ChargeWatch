# ChargeWatch #113 — Q4c-07 lege gefilterde locatie-ID-sets

**3 november 2026. Status: voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd.** Q4c-06 (3/3) is daadwerkelijk geslaagd.

## Doel

De bestaande `MapFilter.TryParse` accepteert een niet-lege operatornaam als filterwaarde, ook als die operator niet voorkomt. Met de bewust niet-bestaande `__Q4_NONEXISTENT_OPERATOR_113__` testen we of zowel de oude viewportselectie als de nieuwe volledige rasterselectie **nul** locaties selecteren, zonder achterblijvende IDs. De query gebruikt dezelfde `MapFilter.LocationPredicate(5/6)` en `AddParameters` als de productiecode. De hele gridselectie wordt uitgevoerd, en de viewport-bbox is `5.50,51.75,5.53,51.77`.

Het is een negatief scenario, dus hier zijn twee lege ID-sets **juist** het doel. Dit test geen uiteindelijke API-response of `coverage.complete`-metadata: de nieuwe endpoint bestaat nog niet. De geldigheid van `coverage.complete=true` bij een lege maar volledig geselecteerde celset moet later expliciet worden geborgd.

## PowerShell op Windows

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c07 |
  Set-Content -Path q4c07-generated.sql -Encoding utf8
Get-Content q4c07-generated.sql -TotalCount 7
scp .\q4c07-generated.sql hinthem@192.168.2.48:~/q4c07-generated.sql
```

## NAS

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c07-generated.sql
```

## Acceptatie

Verwacht `viewport_ids=0`, `grid_candidates=0`, `grid_owned=0`, `overview_clipped_ids=0`, `differing_ids=0`, `viewport_unique=t`, `overview_unique=t`, `ids_match=t`. Als onverwacht een positieve match verschijnt, eerst de geretourneerde operatorwaarde controleren; de selectie is dan niet langer gegarandeerd leeg.

De gegenereerde SQL gebruikt `SET ROLE chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK`. Geen database-/schemamutaties, API-/Android-implementatie, deploy of merge; PR #114 blijft draft.
