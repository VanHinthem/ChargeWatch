# ChargeWatch #113 — P2d: CCS2/DC met locatiegecorreleerde filterevaluatie

**3 november 2026 — SQL-benchmark voorbereid, nog niet gebouwd of op NAS uitgevoerd.** Dit is één gerichte hypothese na P2a–P2c, **geen nieuwe productieimplementatie**.

## Reden

De acht P2c-plannen tonen bij `connectorType=ccs2&mode=dc` voor zowel oorspronkelijk als geography-first steeds een landelijke `Parallel Seq Scan on connector`. Een gewone `geo_locations AS MATERIALIZED` garandeert niet dat PostgreSQL de technische `EXISTS`-voorwaarde per geografisch geselecteerde locatie uitvoert.

De geïsoleerde `MapFilterQ4Probe`-exporter genereert nu voor **alleen CCS2 + DC** een derde queryvorm:

```sql
-- Illustratie van structuur, geen zelfstandig uit te voeren script.
geo_locations AS MATERIALIZED (
  -- bestaande ruimtelijke selectie: volledige Oss-gridcellen; ~2101 locaties
),
selected AS MATERIALIZED (
  SELECT l.id, l.cx, l.cy
  FROM geo_locations l
  CROSS JOIN LATERAL (
    SELECT 1 AS accepted
    WHERE true
      -- Exact MapFilter.LocationPredicate(6), uit gelinkte productiebron
    OFFSET 0
  ) matched
)
```

**Belangrijk:** het daadwerkelijke SQL-script bevat géén handgeschreven filtercondities of SQL-placeholders. De exporter gebruikt dezelfde `MapFilter.TryParse`, `MapFilter.LocationPredicate(6)` en `MapFilter.AddParameters` als P2a/P2b; `OFFSET 0` is uitsluitend een PostgreSQL-queryplannerproef om subquery-flattening te beperken. Het is een hypothese dat dit per-locatie-indexselectie stimuleert; `EXPLAIN` moet het bewijzen. De beschikbaarheidsaggregatie en read-only-scope blijven onveranderd.

## Windows — build en SQL export

```powershell
cd C:\GITHUB\ChargeWatch
git switch docs/location-dataflow-113-design
git pull --ff-only

$project = 'tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj'
dotnet build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build mislukt; stop.' }

$sql = dotnet run --no-build --project $project -- --emit-overview-correlated
if ($LASTEXITCODE -ne 0 -or -not ($sql -match 'P2d correlated location-first with original MapFilter: ccs2_dc')) {
    throw 'Geen geldige P2d SQL gegenereerd; stop.'
}
$sql | Set-Content -Path .\p2d-correlated.sql -Encoding utf8
scp .\p2d-correlated.sql hinthem@192.168.2.48:~/p2d-correlated.sql
if ($LASTEXITCODE -ne 0) { throw 'Upload mislukt; stop.' }
```

## NAS — read-only ID-pariteit en queryplan

```bash
cd /volume2/docker/chargewatch
set -o pipefail
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/p2d-correlated.sql \
  | tee ~/p2d-correlated-result.txt
```

De generator zet `chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK` en `RESET ROLE`. Er is geen DB-schemamutatie of datawijziging.

## Vereiste controles

1. **Pariteit vóór prestatie:** `original_locations = correlated_locations`, `differing_ids=0`, `ids_match=t`; bij falen niet als geslaagde optimalisatie registreren. De bestaande pariteitsquery heeft kolomnaam `geofirst_locations` voor de tweede variant, ook al is deze P2d **correlated**; dat is alleen een outputlabel.
2. **Planner:** bij `selected` wil je een lokale `Nested Loop` of vergelijkbare gecorreleerde uitvoering zien met `idx_evse_active_location` en `idx_connector_active_evse`, zonder landelijke `Parallel Seq Scan on connector`. Het ontbreken hiervan is een negatief performance-experiment, geen filterfout.
3. **Uitvoeringstijd en I/O:** rapporteer `Execution Time`, `Planning Time`, root `Buffers`, hoeveel indexsearches, aantal geselecteerde locaties en rastercellen. Vergelijk P2d niet naïef met eerdere P2c tijden bij wisselende cache. Bij plausibele verbetering is een gemengd/afwisselend P2c/P2d-paar pas voldoende om snelheid beter te beoordelen.
4. **Schaal:** P2d gebruikt alleen het kleine Oss-gebied; zelfs een succesvolle lokale run zegt niets over het landelijke zoom-8-grid. Voor landelijke dekking is een aparte oplopende meetreeks nodig.

**Grenzen:** dit is een read-only, uit C# gegenereerd SQL-onderzoek; geen productie-Npgsql-binding, geen nieuwe API-/Android-/collectorcode, geen status- of filtersemantiekwijziging. PR #114 blijft draft; merge/deploy uitsluitend na expliciet akkoord.


## Correctie exporter — 3 november 2026

Bij eerste Windows-uitvoering meldde de exporter `InvalidOperationException: Correlated filter SQL template not applied`. **Oorzaak:** `geo.Replace(...)` verwachtte exact 12 spaties in een meerregelig C# raw-stringfragment, terwijl de C# raw-string-indentering anders wordt genormaliseerd. **Fix op ontwerpbranch:** vervang uitsluitend de unieke marker `WHERE true __FILTER__`, met een expliciete één-voorkomencontrole. De ingevoegde `CROSS JOIN LATERAL (... WHERE true __FILTER__ OFFSET 0)` behoudt de originele `MapFilter`-predicate. Er is geen database-/productiecodewijziging.

Op Windows opnieuw `git pull --ff-only` en **`dotnet build`** uitvoeren vóór `dotnet run --no-build`. Het uitvoerende script was eerder niet gegenereerd; de NAS-query is dus **nog niet** getest. De fix is in GitHub visueel gecontroleerd, **niet lokaal gecompileerd** in deze assistentomgeving. Stuur nieuwe foutmelding als de build of uitvoer faalt.


## P2d werkelijke NAS-resultaten — 3 november 2026

Gebruiker leverde de complete P2d-output aan na de exporter-fix. **Pariteit: PASS**: `ccs2_dc` oorspronkelijke locatie-ID's 34, P2d 34, `differing_ids=0`, `ids_match=t`. Vier rastercellen.

- **Planning:** 3,063 ms; **Execution:** 160,477 ms.
- **Root buffers:** `shared hit=37353 read=23993 dirtied=2027 written=1824`.
- `geo_locations` materialiseert **2.101 locaties** met partiële GiST `ix_charging_location_active_position` (root geo `shared hit=2925 read=36`).
- Gecorreleerde `selected` CTE: `Nested Loop` met `InitPlan` per locatie; `idx_evse_active_location` **2.101 indexsearches** en `idx_connector_active_evse` **4.838 indexsearches**. **De landelijke `Parallel Seq Scan on connector` is verdwenen.** Selected 34 locaties, read 36 buffers in dit onderdeel.
- **Cruciaal nieuw uitvoeringsknelpunt:** ná de geselecteerde locatie-ID's kiest PostgreSQL voor de availabilityaggregatie een `Hash Right Join` met **`Seq Scan on evse`**: **216.524 actieve EVSE-rijen**, `actual time=0.222..103.365`, scanbuffers `shared hit=4168 read=23957 dirtied=2027 written=1788`. Deze laatste queryfase domineert nu de I/O. De eindjoin produceert 244 rijen, de per-location aggregatie 34 en de eindaggregatie vier cellen.

**Interpretatie:** het P2d-idee is filterparitair en elimineert de *connector*-landelijke scan, maar **eliminiéért niet de landelijke EVSE-scan**. Eén losse uitvoering van 160,477 ms is geen bewezen snelheidswinst tegenover de P2c-original-runs 200,823 en 134,760 ms of P2c-geofirst-runs 136,676/138,138 ms; cache-/I/O-verschillen waren groot. Een volgende queryvariant moet daarom uitsluitend de **laatste availability-aggregatie** bij de 34 geselecteerde locaties indexgericht maken en de bestaande classifier exact behouden. Dit mag een meetproef zijn, geen productie-statusontwerp of nieuwe index.

**Vervolg:** hooguit één read-only, identieke-ID-en-samenvattings-pariteitsproef met geselecteerde locatie→`LATERAL` EVSE-aggregatie; voer daarna `EXPLAIN (ANALYZE, BUFFERS)` uit en test de query later ook op grote/nationale rasterdekking vóór productie-keuze. PR #114 blijft draft; geen schemawijziging/merge/deploy.
