# ChargeWatch #113 — Q4c-08 locatiebrede beschikbaarheidsring

**3 november 2026. Voorbereid, nog niet lokaal gebouwd of op NAS uitgevoerd.** Q4c-07 (lege selectie) is geslaagd. Q5 bronkwaliteitsbesluit staat nog open.

## Doel en bewijsgrens

Selecteer met de bestaande `MapFilter.LocationPredicate(5)` de locaties die een operationele CCS2/DC-connector hebben binnen de bestaande kleine Oss-bbox. Vergelijk voor **dezelfde geselecteerde locaties**:

- `matching_operational`: aantal unieke operationele EVSE's met ten minste één bij het technische filter passende connector, geteld per EVSE via `EXISTS`.
- `all_operational`: aantal **alle** operationele EVSE's op deze geselecteerde locatie, ongeacht connectorcategorie.
- `all_free` en `all_unclassified`: tellingen over alle actieve operationele EVSE's, niet alleen over passende technische connectoren.
- Voorlopige ringklasse groen/rood/onbekend, waarvan per geselecteerde locatie exact één categorie wordt bepaald.

**Bewijsdoel:** `all_operational >= matching_operational` voor elke rij. Een rij met `has_other_evses=t` geeft een positief praktijkvoorbeeld waarin de ringinformatie breder moet zijn dan het technische filter. De samenvatting moet voldoen aan `selected_locations = green_locations+red_locations+unknown_locations` en `partition_ok=t`. Als er geen `has_other_evses=t` voorkomt, blijft een aparte positieve fixture nodig.

**Belangrijke beperking:** deze Q4c-08 is een read-only SQL-audit van het voorgestelde *locatiebrede* rekenpad, niet een daadwerkelijk endpoint (die bestaat nog niet). De tijdelijke statusclassificatie houdt geen definitieve rekening met waarnemingsversheid en bronkwaliteit: bij Q5 moet onbekend/stale conservatief worden geclassificeerd. De ringkleur mag dus nog **niet** als productiecorrect worden goedgekeurd. De filterselectie wordt wel vanuit de echte productie-`MapFilter` gegenereerd. Deze proef is ook geen onafhankelijk bewijs voor de technische `MapFilter`-selectiesemantiek; die is eerder via ID-setvergelijkingen gevalideerd.

## Windows PowerShell

```powershell
cd C:\GITHUB\ChargeWatch
git pull --ff-only
dotnet run --project tools/MapFilterQ4Probe/MapFilterQ4Probe.csproj -- --emit-q4c08 |
  Set-Content -Path q4c08-generated.sql -Encoding utf8
Get-Content q4c08-generated.sql -TotalCount 7
scp .\q4c08-generated.sql hinthem@192.168.2.48:~/q4c08-generated.sql
```

## NAS-shell

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch < ~/q4c08-generated.sql
```

Twee resultsets: detail per geselecteerde locatie, en de categorie-partitietelling. SQL gebruikt `SET ROLE chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout='5s'`, `lock_timeout='1s'`, `jit=off`, `ROLLBACK`. Bij timeout eerst de query herbeoordelen, niet zomaar de limiet verhogen.

## Nog open na deze stap

- Q4c-04c synthetische multi-connector-deduplicatie is voorbereid maar nog niet door gebruiker als uitgevoerd gerapporteerd.
- Onafhankelijke cross-connector-AND/OR fixture, indien nodig.
- Q5 betrouwbare beschikbaarheidsclassificatie, staleness en bronstatus.
- End-to-end overzichtsendpoint, `coverage.complete` en cachegedrag pas in latere implementatiefase.

Geen productiecode-/schemamutaties, NAS-deploy of merge. PR #114 blijft draft.
