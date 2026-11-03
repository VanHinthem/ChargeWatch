# ChargeWatch #113 — Q4c-04b onafhankelijke EVSE-deduplicatie

**3 november 2026. Voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd.**

## Doel

Q4c-04 selecteerde in de kleine Oss-bbox **één** laadlocatie voor `connectorType=ccs2&mode=dc&power=gte150&minFree=2`, met exact dezelfde locatie-ID in beide kaartselecties. Deze aanvullende read-only proef telt onafhankelijk via `JOIN connector` en `GROUP BY location_id, evse_id` de werkelijk passende **unieke** `AVAILABLE` EVSE's. Daardoor kan één EVSE met meerdere connectoren nooit als twee EVSE's gelden.

De SQL verwijst naar de productie-`MapFilter.EffectiveWattsSql`-formule voor het vermogen en gebruikt bewust niet de productie-`MapFilter.LocationPredicate`-EXISTS/count-query: een onafhankelijke vergelijkingsroute is nodig om een gedeelde tellerfout uit te sluiten. De testcondities zijn bewust vastgezet op CCS2, DC en >=150 kW; dit is geen tweede productie-filterimplementatie.

## 1. Windows PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c04b |
  Set-Content -Path q4c04b-generated.sql -Encoding utf8
Get-Content q4c04b-generated.sql -TotalCount 7
scp .\q4c04b-generated.sql hinthem@192.168.2.48:~/q4c04b-generated.sql
```

## 2. NAS

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c04b-generated.sql
```

De gegenereerde SQL gebruikt de `chargewatch_read`-rol, een `REPEATABLE READ READ ONLY`-transactie, `statement_timeout=5s`, `lock_timeout=1s`, `jit=off` en `ROLLBACK`. Geen schema- of datamutatie.

## Verwachte uitvoer en acceptatie

De eerste resultset geeft per onafhankelijk passende locatie `id`, `feature_id`, `qualifying_connector_rows`, `distinct_qualifying_evses`, `evses_with_multiple_qualifying_connectors` en `passes_min_free_2`. De tweede resultset geeft geaggregeerde aantallen.

Een positieve bevestiging voor de Q4c-04-locatie vereist **precies één** onafhankelijke locatie in deze bbox, `distinct_qualifying_evses >= 2`, `passes_min_free_2=t`, en hetzelfde `feature_id` als de eerder gegenereerde gefilterde locatie-ID (indien afzonderlijk vastgelegd). Zonder opgeslagen referentie-ID bewijst `locations_matching_independent=1` op zichzelf geen exacte ID-overeenkomst; die vergelijking kan daarna expliciet worden toegevoegd.

De connectorrow-teller mag groter zijn dan het unieke EVSE-aantal; juist dat is de relevante check op mogelijke connector-duplicatie. Als er geen meerdere connectoren voorkomen, bewijst de proef het juiste tellingsmechanisme bij de aanwezige data, maar geen positieve multi-connector fixture. Synthetische duplicatiecontrole blijft dan aanvullend nodig.

**Belangrijk:** de SQL bevat opzettelijk twee SELECTs die binnen dezelfde snapshot blijven, maar het eerste SELECT-resultaat is per locatie en het tweede een samenvatting. Nog geen claims over resultaten voordat de NAS-uitvoer daadwerkelijk is gecontroleerd. Q4-05..08 en Q5 blijven open; PR #114 draft, geen merge of deployment.


## Werkelijke NAS-uitvoer — 3 november 2026

De gebruiker heeft de gegenereerde read-only query daadwerkelijk op productie uitgevoerd. De enige passende rij was:

| id | feature_id | qualifying_connector_rows | distinct_qualifying_evses | evses_with_multiple_qualifying_connectors | passes_min_free_2 |
|---:|---|---:|---:|---:|---|
| 81262 | NL-BPE-6d278e19-8278-4a1d-9609-45025fca3a8a | 2 | 2 | 0 | t |

Samenvatting: `locations_matching_independent=1`, `qualifying_connector_rows=2`, `qualifying_unique_evses=2`, `locations_with_multi_connector_matches=0`.

**Q4c-04b PASS voor de onafhankelijke positieve EVSE-telling:** er is precies één passende locatie met twee unieke beschikbare EVSE's die ieder ten minste één passende connector hebben. Omdat de Q4c-04-resultaatset slechts aantallen en geen feature-ID rapporteerde, is die eerdere ID niet rechtstreeks naast de audit-ID gezet; het resultaat ondersteunt dezelfde unieke match binnen de identieke kleine bbox, maar is geen afzonderlijke vergelijking van twee gerapporteerde ID-waarden. **Dubbeltelling bij meerdere passende connectoren op één EVSE is niet empirisch beproefd**: hier zijn er 2 connectorrijen en 2 EVSE's. Volgende stap is een kleine synthetische read-only duplicatiefixture. Geen productiemutatie, schemawijziging, merge of deployment.
