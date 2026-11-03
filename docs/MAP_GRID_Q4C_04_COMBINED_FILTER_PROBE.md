# ChargeWatch #113 — Q4c-04 gecombineerde technische filters en minFree

**3 november 2026. Voorbereid, nog niet uitgevoerd.**

## Doel
Controleer met de productie-`MapFilter` SQL-generator de exacte precluster locatie-ID-set voor `connectorType=ccs2&mode=dc&power=gte150&minFree=2`. Alleen unieke `AVAILABLE` operationele EVSE's met een passende connector mogen meetellen voor `minFree`. De nieuwe rasterselectie wordt naar dezelfde kleine viewport teruggeclipt. Beide selecties worden onder één read-only `REPEATABLE READ`-snapshot vergeleken. Deze check gebruikt de bestaande filtergenerator, geen aparte filterlogica. Q4c-03 was geslaagd: 86/86 voor minFree=1 en 54/54 voor minFree=2, beide `differing_ids=0`.

## Windows PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c04 |
  Set-Content -Path q4c04-generated.sql -Encoding utf8
Get-Content q4c04-generated.sql -TotalCount 7
scp .\q4c04-generated.sql hinthem@192.168.2.48:~/q4c04-generated.sql
```

## NAS-shell

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c04-generated.sql
```

## Acceptatie

`differing_ids=0`, beide uniekheidscontroles `t`, `ids_match=t`. **Een nul/nul-uitkomst betekent geen positieve end-to-end testcase** voor een locatie met twee kwalificerende EVSE's. Bij nul resultaten zoeken we vervolgens gecontroleerd naar een geschikte kleine testviewport of kiezen een aanvullende synthetische één-EVSE-per-rij fixture; we verklaren Q4-04 dan niet volledig afgedekt. De SQL gebruikt `SET ROLE chargewatch_read`, `BEGIN ... REPEATABLE READ READ ONLY`, `statement_timeout=5s`, `lock_timeout=1s`, `jit=off` en `ROLLBACK`. Geen DB-schemamutatie, deploy of merge.

## Aanvullend bewijs dat later nodig blijft
De twee selecties delen dezelfde `MapFilter` SQL. Exacte ID-setpariteit bewijst dus niet onafhankelijk dat `minFree` correct per unieke EVSE telt of dat statusringen over **alle operationele EVSE's van de geselecteerde locatie** gaan; controleer dat met aparte Q4-04/Q4-08 fixtures. Q4-05 operatoren, Q4-06 multivalue, Q4-07 lege resultaten en Q5 bronkwaliteit volgen.
