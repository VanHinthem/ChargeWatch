# Sprint 3 — statistiekfundament (ontwerp ter beoordeling)

**Issue:** #11 · **Branch:** `feature/sprint3-observation-foundation`  
**Status:** schema- en observatiecontract voorgesteld, nog geen DB-migratie of importintegratie.

## Semantiek die vanaf dag één goed moet zijn

- NDW levert een momentopname, **geen gegarandeerde volledige eventgeschiedenis**. Gebruik de bron- `last_updated` om oude statussen af te wijzen en de import-/waarnemingstijd om de observatieafstand te meten.
- Eerste observatie stelt uitsluitend het actuele checkpoint vast. Geen tijd terugrekenen vanaf het eerste bronmoment.
- Ongewijzigde bronbestanden (304/304), tarief-only imports en identieke bron-/statusmomenten mogen geen extra meetduur opleveren.
- Een interval is alleen bruikbaar als beide observaties valide zijn en er geen te groot gat is; stel maximaal 15 minuten voor bij 5-minuten polling. Interval met statuswisseling heeft onbekend omslagmoment: tel niet het volledige interval bij vorige of nieuwe status.
- Alleen definitief en compleet waargenomen locatietoestanden mogen de `>=1 vrij` statistiek voeden. Een locatie met 0 AVAILABLE en minstens één UNKNOWN is **onbepaald**, niet 'vol'.
- `AVAILABLE` = vrij; `CHARGING`, `RESERVED`, `BLOCKED` = niet vrij maar operationeel (niet noodzakelijk laden); `INOPERATIVE`, `OUTOFORDER` = storing; `PLANNED`, `REMOVED`, `UNKNOWN`, onbekende waarden en missende status = onbekend. Uptime-noemer bevat alleen operationele en storingsduur.
- Locatieprofielen moeten uit gelijktijdige statussen van alle EVSE's komen, niet uit vermenigvuldiging van afzonderlijke kansen.
- Opslag blijft compact: 168 vakken per EVSE/locatie, een rollend 90-daags dagvenster en vaste duurbuckets, geen onbeperkte eventrijen. Correcties/backfills niet als nieuwe duur tellen.

## Voorgestelde tabellen (nog niet toepassen)

| Tabel | Sleutel | Opslag |
|---|---|---|
| `evse_observation_checkpoint` | `evse_id` | laatste verwerkte status, bronmoment, waarnemingstijd, kwaliteitsvlag |
| `location_observation_checkpoint` | `location_id` | aantal EVSE's, beschikbare/defecte/onbekende status, laatste valide locatiemoment |
| `evse_occupancy_profile` | `evse_id` | 168 EWMA-waarden, tellers/dekking, laatste geldige observatie |
| `location_occupancy_profile` | `location_id` | 168 rechtstreeks gemeten vrije-kans-waarden, dekkingsindicatoren |
| `evse_daily_reliability` | `evse_id, date` | operationele, defecte, onbekende seconden; maximaal 90 dagen |
| `evse_duration_summary` | `evse_id` | compacte duurbuckets per toestandsklasse, overgangstellingen |
| `location_zero_available_summary` | `location_id` | compacte duurverdeling van 0-vrij-perioden, herstel-/kwaliteitscounters |

Compacte arrays versus per-slotrijen worden pas gekozen op basis van echte databasebenchmarks. Schrijfrechten blijven bij de importer; de toekomstige API krijgt alleen SELECT.

## Transactionele verwerking (na schema-akkoord)

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
