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


## Werkelijke NAS-uitvoer — 3 november 2026

Gebruiker voerde de gegenereerde read-only SQL daadwerkelijk uit. **15/15 synthetische fixtures hadden `value_ok=t` en `all_values_ok=t`**, inclusief 49.999/50.000, 149.999/150.000, 299.999/300.000 W, AC-éénfase, AC-driefase, DC, expliciete voorrang, nul-fallback en ontbrekende gegevens. De vier bandbooleans waren conform de verwachte drempels; `NULL` matchte geen vermogensband.

Werkelijke connectortelling:

| active_connectors | explicit_positive | calculated_positive | unknown_power | exactly_50kw | exactly_150kw | exactly_300kw |
|---:|---:|---:|---:|---:|---:|---:|
| 216262 | 144940 | 67893 | 3429 | 1116 | 1862 | 1318 |

De categorieën expliciet/berekend/onbekend sommeren tot 216.262: 67,0%, 31,4%, 1,6% afgerond. Grenswaarden zijn dus daadwerkelijk in de brondata aanwezig. **Q4c-02b PASS voor deze 15 synthetische grens-/fallbackcases en geobserveerde productie-distributie.** Deze telling toont niet onafhankelijk alle ruwe NDW-vermogensvelden juist te zijn; Q5 bronkwaliteitscontract blijft apart. Er waren geen muterende databasehandelingen, schemawijzigingen, deploy of merge.
