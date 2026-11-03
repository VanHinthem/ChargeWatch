# ChargeWatch #113 — Q4c-06 multivalue connector- en modusfilterpariteit

**3 november 2026. Status: voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd.**

## Doel
Test de exacte locatie-ID-set voor combinaties met meerdere `connectorType`- en `mode`-waarden, via de bestaande productie-`MapFilter.TryParse`, `LocationPredicate(5/6)` en `AddParameters`. Binnen één veld geldt OR, tussen verschillende velden AND. De connectorcategorie en modus moeten door **dezelfde connectorrij** worden gematcht; een andere connector op de EVSE mag niet onbedoeld de combinatie laten slagen. De huidige proef vergelijkt de bestaande SQL-filterselectie met de complete rasterselectie na clipping naar de kleine Oss-bbox. Een onafhankelijk synthetisch tegenvoorbeeld voor cross-connector-match volgt nog indien nodig.

## Drie gevallen
- `connectorType=ccs2&connectorType=chademo&mode=ac&mode=dc`
- `connectorType=type2&connectorType=ccs2&mode=ac&mode=dc`
- `connectorType=ccs2&connectorType=chademo&mode=dc`

Omdat de test de twee selecties met de centrale generator maakt, wordt geen aparte productie-filterimplementatie toegevoegd. Beide selecties gebruiken hetzelfde snapshot per testcase (`REPEATABLE READ READ ONLY`), `chargewatch_read`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off` en `ROLLBACK`.

## Windows PowerShell
```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c06 |
  Set-Content -Path q4c06-generated.sql -Encoding utf8
Get-Content q4c06-generated.sql -TotalCount 7
scp .\q4c06-generated.sql hinthem@192.168.2.48:~/q4c06-generated.sql
```

## NAS
```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c06-generated.sql
```

## Acceptatie
Per case: `differing_ids=0`, `viewport_unique=t`, `overview_unique=t`, `ids_match=t`. We rapporteren de werkelijk geselecteerde aantallen; nul matches in een case zijn geen positief bewijs voor die combinatie. De resultaten bewijzen selectiepariteit bij deze testdata, niet onafhankelijk de correcte AND/OR-evaluatie als er een gedeelde bug in de productie-`MapFilter` zit. Q4-07 lege resultaten, Q4-08 locatiebrede ringclassificatie, Q4c-04c synthetische EVSE-deduplicatie en Q5 bronkwaliteit blijven open. Geen schemawijziging, deployment of merge.
