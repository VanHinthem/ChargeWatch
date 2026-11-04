# ChargeWatch #113 — Q4c-03 minFree=1/2 ID-set-pariteit

**3 november 2026. Voorbereid, niet uitgevoerd.** De bestaande `MapFilterQ4Probe` heeft nu `--emit-q4c03`. Het gebruikt **dezelfde productie-`MapFilter.cs`** met `LocationPredicate(5)` voor viewport en `LocationPredicate(6)` voor het grid, en roept `AddParameters` voor beide querys aan. De read-only export bevat twee onafhankelijke SQL-transacties voor `minFree=1` en `minFree=2`. Dezelfde kleine Oss-viewport `5.50,51.75,5.53,51.77` wordt vergeleken na het terugclippen van hele gridcellen.

## Windows PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c03 |
  Set-Content -Path q4c03-generated.sql -Encoding utf8
Get-Content q4c03-generated.sql -TotalCount 6
scp .\q4c03-generated.sql hinthem@192.168.2.48:~/q4c03-generated.sql
```

## NAS

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c03-generated.sql
```

## Acceptatie en betekenis

Per rij verwachten we `differing_ids=0`, `viewport_unique=t`, `overview_unique=t`, `ids_match=t`. Noteer of `viewport_ids` positief is. Twee leeg geselecteerde sets zijn niet genoeg als positieve fixture; dan kiezen we aanvullende echte locatiegegevens. De twee SELECTs binnen elk scenario gebruiken **één REPEATABLE READ READ ONLY snapshot**, `chargewatch_read`, `statement_timeout=5s`, `lock_timeout=1s`, `jit=off`, afsluiting met `ROLLBACK`. De test hergebruikt de bestaande telling van unieke EVSE-rijen binnen `MapFilter`; dit scenario heeft **geen technische filters**. De combinatie technische filters + `minFree` is apart Q4-04. De eerdere onafhankelijke SQL-aggregatie Q4a was reeds geslaagd voor CCS2/DC minFree=1/2 binnen een andere Oss-bbox; dat is aanvullend, niet hetzelfde bewijs.

Geen productiedatabasewrites, schemawijziging, API/Android-implementatie, deploy of merge; PR #114 blijft draft.


## Werkelijk NAS-resultaat — 3 november 2026

De gebruiker voerde beide read-only SQL-tests daadwerkelijk uit:

| Fixture | viewport_ids | grid_candidates | grid_owned | overview_clipped_ids | differing_ids | viewport_unique | overview_unique | ids_match |
|---|---:|---:|---:|---:|---:|---|---|---|
| minFree=1 | 86 | 380 | 380 | 86 | 0 | t | t | t |
| minFree=2 | 54 | 266 | 266 | 54 | 0 | t | t | t |

**Q4c-03 PASS (2/2)**: beide positieve locatie-ID-sets komen exact overeen en bevatten geen dubbele IDs binnen de geteste Oss-bbox. Dit bevestigt locatie-inclusiepariteit bij de bestaande `MapFilter`-semantiek, niet op zichzelf een onafhankelijke EVSE-brontelling of bronkwaliteitsclassificatie. Volgende Q4c-04: technische filters samen met `minFree`. Geen productie- of schemawijzigingen, deployment of merge.
