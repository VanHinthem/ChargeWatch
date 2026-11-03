# ChargeWatch #113 — Q4c-02b: vermogen grenswaarden en brondata

**3 november 2026. Status: voorbereid, nog niet uitgevoerd.**

Q4c-02 leverde daadwerkelijk 4/4 exacte viewport/grid-ID-setovereenkomsten op: `lt50` 97/97, `gte50` 1/1, `gte150` 1/1 en `gte300` 0/0; alle vier `differing_ids=0`. Dit bewijst nog niet onafhankelijk dat de centrale vermogensberekening inhoudelijk juist is.

Deze read-only test genereert SQL met de **exacte** `MapFilter.EffectiveWattsSql` uit productiecode (geen gekopieerde rekenformule). Een synthetische `VALUES`-fixture controleert expliciete vermogens precies onder/op 50, 150 en 300 kW, afgeleid DC-vermogen, AC-éénfase en -driefase, expliciet-vermogen-voorrang, nulwaarde-fallback en ontbrekende gegevens. Een aparte aggregatie telt in de echte connector-tabel expliciete, afgeleide en onbekende vermogens en exact voorkomende grenswaarden. **Geen INSERT/UPDATE, geen schemawijziging.**

## 1. Windows PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c02b |
  Set-Content -Path q4c02b-generated.sql -Encoding utf8
Get-Content q4c02b-generated.sql -TotalCount 6
scp .\q4c02b-generated.sql hinthem@192.168.2.48:~/q4c02b-generated.sql
```

## 2. NAS-shell

```bash
cd /volume2/docker/chargewatch
ls -l ~/q4c02b-generated.sql
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c02b-generated.sql
```

De SQL voert `SET ROLE chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK` en `RESET ROLE` uit. Bij timeout of fout geen limieten ophogen zonder nieuw overleg.

## Interpretatie

- Synthetische fixtures: voor **iedere** rij `value_ok=t` en `all_values_ok=t`; de verwachte inclusie is strikt `lt50: 0 < W < 50000`, `gte50: W >= 50000`, `gte150: W >= 150000`, `gte300: W >= 300000`. Vermogen onbekend levert geen match op.
- Brondata: `active_connectors`, `explicit_positive`, `calculated_positive`, `unknown_power`, `exactly_50kw`, `exactly_150kw`, `exactly_300kw` zijn **observaties**, geen vooraf vastgelegde aantallen. Ze helpen bepalen hoeveel echte data de grenswaarde-/fallbackregels raakt.
- De synthetische voorbeelden dekken representatieve formulepaden, maar niet elke rekenkundige afronding/overflowgrens. Als een fixture faalt, eerst echte SQL en bedoelde `ConnectorPower.Estimate` semantiek beoordelen; geen productiemutaties.
- Q4-03 t/m Q4-08 en Q5 blijven open. De huidige `gte300` viewport had nul matches; een latere positieve settest op groter/gepast gebied is wenselijk.

De generator is toegevoegd aan de ontwerpbranch, maar **niet lokaal gebouwd en niet op de NAS uitgevoerd**. PR #114 blijft draft; geen merge/deploy.
