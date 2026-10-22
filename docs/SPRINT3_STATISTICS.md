# Sprint 3 — statistiekfundament (ontwerp ter beoordeling)

**Issue:** #11 · **Branch:** `feature/sprint3-observation-foundation`  
**Status:** migratie, checkpoints en conservatieve uptime-dagaccumulatie in code. Niet tegen PostgreSQL getest of toegepast; 168-slot EWMA, 90-daagse retentie en duurstatistieken volgen.

## Semantiek die vanaf dag één goed moet zijn

- NDW levert een momentopname, **geen gegarandeerde volledige eventgeschiedenis**. Gebruik de bron- `last_updated` om oude statussen af te wijzen en de import-/waarnemingstijd om de observatieafstand te meten.
- Eerste observatie stelt uitsluitend het actuele checkpoint vast. Geen tijd terugrekenen vanaf het eerste bronmoment.
- Ongewijzigde bronbestanden (304/304), tarief-only imports en identieke bron-/statusmomenten mogen geen extra meetduur opleveren.
- Een interval is alleen bruikbaar als beide observaties valide zijn en er geen te groot gat is; stel maximaal 15 minuten voor bij 5-minuten polling. Interval met statuswisseling heeft onbekend omslagmoment: tel niet het volledige interval bij vorige of nieuwe status.
- Alleen definitief en compleet waargenomen locatietoestanden mogen de `>=1 vrij` statistiek voeden. Een locatie met 0 AVAILABLE en minstens één UNKNOWN is **onbepaald**, niet 'vol'.
- `AVAILABLE` = vrij; `CHARGING`, `RESERVED`, `BLOCKED` = niet vrij maar operationeel (niet noodzakelijk laden); `INOPERATIVE`, `OUTOFORDER` = storing; `PLANNED`, `REMOVED`, `UNKNOWN`, onbekende waarden en missende status = onbekend. Uptime-noemer bevat alleen operationele en storingsduur.
- Locatieprofielen moeten uit gelijktijdige statussen van alle EVSE's komen, niet uit vermenigvuldiging van afzonderlijke kansen.
- Opslag blijft compact: 168 vakken per EVSE/locatie, een rollend 90-daags dagvenster en vaste duurbuckets, geen onbeperkte eventrijen. Correcties/backfills niet als nieuwe duur tellen.

## Geïmplementeerde tabellen (nog niet op databases toegepast)

| Tabel | Sleutel | Opslag |
|---|---|---|
| `evse_observation_checkpoint` | `evse_id` | laatste verwerkte status, bronmoment, waarnemingstijd, kwaliteitsvlag |
| `location_observation_checkpoint` | `location_id` | aantal EVSE's, beschikbare/defecte/onbekende status, laatste valide locatiemoment |
| `evse_occupancy_profile` | `evse_id` | 168 EWMA-waarden, tellers/dekking, laatste geldige observatie |
| `location_occupancy_profile` | `location_id` | 168 rechtstreeks gemeten vrije-kans-waarden, dekkingsindicatoren |
| `evse_daily_reliability` | `evse_id, date` | operationele, defecte, onbekende seconden; maximaal 90 dagen |
| `evse_duration_summary` | `evse_id` | compacte duurbuckets per toestandsklasse, overgangstellingen |
| `location_zero_available_summary` | `location_id` | compacte duurverdeling van 0-vrij-perioden, herstel-/kwaliteitscounters |

De migratie kiest voorlopig 168-vaks PostgreSQL-arrays met NULL voor nog niet gemeten slots en compacte 6-vaks duurhistogrammen. Grootte en updatekosten moeten nog met de landelijke benchmark worden gemeten. Schrijfrechten blijven bij de importer; de toekomstige API krijgt alleen SELECT.

## Transactionele verwerking (deels geïmplementeerd)

1. Nieuwe Locations-bron succesvol valideren en bulk staging vullen.
2. Binnen dezelfde PostgreSQL-transactie de vorige checkpoints vergelijken met inkomende statussen/tijdstempels.
3. Alleen aantoonbaar valide observatie-intervallen classificeren, splitsen per uur/dag in `Europe/Amsterdam` (DST expliciet testen).
4. Eén tijdgewogen urenresultaat per passend weekdag/uurslot verwerken; **maximaal één** EWMA-update per uurblok, `α=0,20`.
5. Compacte duurstatistieken en 90-daagse dagtotalen bijwerken; checkpoints atomair doorzetten met catalogus/import_run.
6. Bij rollback blijven statistieken en checkpoint ongewijzigd. Bij herhaling/replay wordt geen tijd dubbel geteld.

**Let op:** dit is een voorstel, geen claim van reeds uitgevoerde implementatie of schema-validatie.

## Validatie voordat Sprint 3 mergeklaar is

- Statusmapping: AVAILABLE, CHARGING, BLOCKED, RESERVED, INOPERATIVE, OUTOFORDER en UNKNOWN.
- Locatie met AVAILABLE + UNKNOWN is wel aantoonbaar vrij; uitsluitend BLOCKED/CHARGING is niet vrij; uitsluitend UNKNOWN of gemengde niet-vrije + UNKNOWN is onbepaald.
- Eén locatie met 2 EVSE's, wijziging naar nul vrije en herstel; geen fictieve vrij-status en geen foutieve volledige periode na gaps.
- Schietspoel-fixture, identieke replay, mislukkende bron, rollover van dag/uur/DST, dataset met verdwenen EVSE.
- Een kopie van de bestaande benchmarkdatabase; controleer performance en groei, geen database/volumes verwijderen.

## Scopegrens

Sprint 3 is **niet** de latere geavanceerde voorspelling (15/30/60 minuten). API/Android volgen afzonderlijk. Schemawijziging en database-uitvoering pas na inhoudelijke validatie door gebruiker.


## Migratie uitvoeren en valideren — uitsluitend geïsoleerde database

**Niet uitvoeren op `chargewatch` of `chargewatch_benchmark` zonder expliciete keuze voor die database.** De migratie is additief, maar vraagt extra opslag en verandert schema/rechten.

Eerst een afzonderlijke proefdatabase op basis van een bestaande veilige kopie voorbereiden. De migratie uitvoeren als eigenaar, niet als `chargewatch_import`:

```powershell
# Bijvoorbeeld bij een expliciet voorbereide chargewatch_statistics_test-database:
Get-Content -Raw .\db\migrations\003_statistics.sql |
    docker compose exec -T db psql -v ON_ERROR_STOP=1 -1 -U chargewatch_owner -d chargewatch_statistics_test
```

Test vervolgens in dezelfde testdatabase:

```powershell
docker compose exec -T db psql -U chargewatch_owner -d chargewatch_statistics_test -c "SELECT version FROM schema_migration WHERE version='003_statistics';"
docker compose exec -T db psql -U chargewatch_owner -d chargewatch_statistics_test -c "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('evse_observation_checkpoint','location_observation_checkpoint','evse_occupancy_profile','location_occupancy_profile','evse_daily_reliability','evse_duration_summary','location_zero_available_summary');"
```

Verwacht één migration-record en zeven nieuwe tabellen. Herhaal de migratie op dezelfde **testdatabase** en controleer dat er geen duplicaten of destructieve wijzigingen zijn. Controleer de SELECT-rol afzonderlijk met de testaccountcredentials. Geen nieuwe migratie wordt automatisch door `docker-entrypoint-initdb.d` op bestaande volumes uitgevoerd.

**Nog uit te voeren:** SQL-integrationtests met echte PostgreSQL, opslagmeting, importintegratie, EWMA- en duurcalculaties, gap-/replaychecks. Geen enkele statische array is al bevolkt.


## Implementatiestatus checkpointfase (22 oktober 2026)

`ObservationCheckpoints.Capture(db, tx)` is aan `BulkImporter.Run` gekoppeld vlak vóór `import_run` en `CommitAsync`. Dezelfde transactie beschermt catalogus en checkpoint-rijen. De transactie faalt expliciet als migratie `003_statistics` niet aanwezig is; **bestaande bulkimports naar een ongemigreerde database werken met deze branch dus niet**. Gebruik voor deze branch een geïsoleerde gemigreerde proefdatabase. `main` en reeds gedeployde processen worden niet gewijzigd.

- EVSE-checkpoints worden alleen aangemaakt/vernieuwd bij een aanwezige, strikt nieuwere bron-`last_updated`; eerste registratie creëert geen fictieve verblijfsduur.
- Locatie-checkpoints worden uit de volledige geüploade EVSE-snapshot berekend. Bij minstens één `AVAILABLE` is de locatie aantoonbaar vrij; bij geen `AVAILABLE` en minstens één onbekende status blijft uitkomst `NULL`.
- Locaties zonder complete timestamps, zonder nieuwe EVSE-timestamp of met een achterlopende EVSE-brontimestamp worden niet als nieuwe locatie-observatie gepubliceerd.
- Checkpointvelden voor duur/continuïteit blijven bewust `NULL/false`, tot de intervalvalidatie is ingebouwd. 168-slotprofielen en duurstatistieken blijven nog leeg.

**Nog niet gevalideerd:** lokale .NET-build, PostgreSQL-migratie, nationale performance, herhaalimport, crash/rollback met echte data. Er is geen automatische CI. Deze PR blijft draft.


## Uptime-dagaccumulatie — implementatie onder test

`ObservationCheckpoints.Capture` registreert nu ook een interval in `evse_daily_reliability` als **beide** EVSE-brontijdstempels aanwezig zijn, de tweede strikt nieuwer is, de statusklasse gelijk blijft, het verschil maximaal 15 minuten bedraagt, en beide bronmomenten binnen dezelfde **UTC-dag** liggen. Voorlopig blijven grensoverschrijdende intervallen ongebruikt in plaats van ze foutief aan één dag toe te kennen. Deze UTC-variant is een voorzichtige technische tussenstap, **nog niet** de afgesproken `Europe/Amsterdam`-dagsemantiek.

- `available` en `occupied` tellen als operationele seconden; `fault` als storing; `unknown` als onbekende tijd, niet in de uptime-noemer.
- Veranderende status, lange gaten, achterlopende of identieke bronmomenten leveren **nul extra seconden** op.
- Verwerking vindt vóór het verversen van EVSE-checkpoints plaats, zodat oude status en bronmoment nog beschikbaar zijn. Ook de locatiecheck tegen verouderde bronmomenten wordt vóór checkpoint-writes gematerialiseerd.
- Alle inserts/updates gebeuren in dezelfde bulkimporttransactie. Een herhaalde bronversie kan na commit geen tweede keer dezelfde intervalduur bijschrijven.

**Nog niet geïmplementeerd:** kalenderdag-splitsing/DST `Europe/Amsterdam`, 90-daagse retentie, representatieve dekkingskwaliteit, EWMA-uurvakken, locatie-intervallen en duurbuckets. Daarom zijn de cijfers nog **niet geschikt voor weergave in de app**.


## Vervolg: Nederlandse datumgrenzen en DST (22 oktober 2026)

De betrouwbaarheid-intervalverdeling gebruikt nu PostgreSQL-`Europe/Amsterdam` lokale middernachten en splitst het aantal werkelijk verstreken seconden per kalenderdag. De code houdt daardoor rekening met 23- en 25-uursdagen. Eerdere documentatie over UTC-dagtotalen beschrijft een inmiddels vervangen tussenstand.

**Essentiële kwaliteitseis:** `last_updated` is een bronstatusmoment, geen bewijs dat de toestand iedere vijf minuten actief is geobserveerd. Daarom tellen uitsluitend intervallen met twee nieuwe, onderling consistente bronmomenten van maximaal 15 minuten. Op onveranderde statusmomenten ontstaat hiermee mogelijk weinig dekking. Dit vereist verificatie met echte NDW-reeksen vóór we uptime als representatief presenteren. `observed_at` alleen gebruiken om versheid/checkpoint te bewaken, niet om onbewezen historische duur te fabriceren.

Nog te verifiëren met PostgreSQL: 23-/25-uurs DST-dagen, lokale-middernachtsplitsing, identieke replay zonder dubbeltelling, ongeldige timestamps, statusverandering en transactie-rollback. De 168 uurprofielen en definitieve publicatiedrempels volgen pas na deze controles.


## Database-smoketest beschikbaar

`db/tests/003_statistics_smoke.sql` bevat gerichte SQL-asserties voor zeven statistiektabellen, 23-/25-uursdagen rond DST, een kort lokaal middernachtinterval en idempotente fixture-inserts. Het script gebruikt `BEGIN`/`ROLLBACK` en laat bij succes geen fixturedata achter.

Na toepassen van de migratie op een **afzonderlijke testdatabase**, voer uit als eigenaar:

```powershell
Get-Content -Raw .\db\tests\003_statistics_smoke.sql |
    docker compose exec -T db psql -v ON_ERROR_STOP=1 -U chargewatch_owner -d chargewatch_statistics_test
```

**Niet als volledige end-to-end-test behandelen**: de bulkimport, productievolume, bronversheid, datumverdeling door `ObservationCheckpoints` en exacte replay-controle vereisen nog daadwerkelijke integratietests. Het script is geschreven maar niet uitgevoerd; de agentomgeving heeft geen `dotnet`, `psql` of `docker`.
