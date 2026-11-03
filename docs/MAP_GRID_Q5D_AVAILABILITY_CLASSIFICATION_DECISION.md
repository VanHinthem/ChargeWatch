> **Geparkeerd voor issue #113 — 3 november 2026.** Dit eerdere Q5d-document is een niet-goedgekeurd onderzoeksconcept, **geen vervanging van bestaande NDW-/API- of beschikbaarheidslogica**. De gebruiker heeft bevestigd dat #113 na Q1–Q4 verder moet met performance-optimalisatie van server-side clustering. Zie [MAP_GRID_PERFORMANCE_NEXT_STEPS.md](MAP_GRID_PERFORMANCE_NEXT_STEPS.md) voor het leidende vervolg. Bestaande regels blijven leidend; dit document mag niet als implementatiespecificatie worden gebruikt zonder apart besluit.

# ChargeWatch #113 — Q5d concept: beschikbaarheidszekerheid en ringclassificatie

**3 november 2026. Status: voorstel voor beoordeling; nog geen definitief akkoord, geen productieimplementatie.**

## Bewijs uit Q5a–Q5c

- Q5a: 206 actieve EVSE's, 2 lifecycle-uitgesloten, 204 operationeel; 151 AVAILABLE, 48 bezet/geblokkeerd, 1 defect, 4 UNKNOWN; statuspartitie geslaagd.
- Q5b: 98 locaties, 86 ten minste één AVAILABLE zonder UNKNOWN, 10 geen AVAILABLE met bekende statussen en 2 zonder AVAILABLE met UNKNOWN. Laatstgenoemde twee mogen niet als zeker bezet worden gepresenteerd.
- Q5c: globale feedobservatie 3,5 minuten oud bij meting; 204 operationele EVSE's waarvan 200 een bronupdate ouder dan 15 minuten hebben. Bronupdates zijn geen nieuwe feedobservaties. De sinds Q5b gewijzigde AVAILABLE/CHARGING-aantallen volgen uit verschillende snapshots.
- Q4c-08: locatiebrede telling voor de twee CCS2/DC-geselecteerde locaties klopte; een positieve live case met extra niet-passende EVSE's ontbrak. Q4c-08b en Q4c-04c synthetische tests blijven open.

## Voorgesteld tweelaags beslismodel

**Laag A — datakwaliteit:** bepaal of de locatie waargenomen en volledig genoeg is. Een recente onafhankelijke, succesvol gevalideerde NDW-locatieswaarneming is een positief signaal. Een oude `source_last_updated` zonder een recente statuswijziging mag op zichzelf geen afkeur zijn. Een globale checkpoint is alleen een landelijk feed-signaal; niet zonder meer bewijs voor lokale/individuele volledigheid. Ontbrekende/mislukte/hergebruikte waarnemingen zijn afzonderlijk bewijsproblemen. De huidige `CW_API_STALE_AFTER_MINUTES`-default 15 minuten is een bestaande API-instelling, niet een automatisch goedgekeurde Q5-sla.

**Laag B — gerapporteerde status na voldoende datakwaliteit:** de operationele EVSE-set van *alle geselecteerde locaties* bepalen, onafhankelijk van technische filters. Gebruik per unieke EVSE de actueel gepubliceerde categorie; `PLANNED`/`REMOVED` en inactieve presence buiten de operationele capaciteit. Maak statuszekerheid en werkelijke bezetting niet onterecht gelijk.

| Situatie | Voorgestelde kaartbetekenis | Zekerheid / UX |
|---|---|---|
| Betrouwbare locatie, minstens één `AVAILABLE`, geen UNKNOWN | Groen: minstens één vrij | Vrije capaciteit positief bekend |
| Betrouwbare locatie, minstens één `AVAILABLE`, daarnaast UNKNOWN | Groen voor aantoonbaar beschikbare capaciteit; onbekende capaciteit apart melden | Geen claim dat alle capaciteit bekend is |
| Betrouwbare locatie, nul AVAILABLE, geen UNKNOWN, actieve EVSE's allen `CHARGING`/`BLOCKED`/`RESERVED` | Rood: geen vrij | Niet allemaal letterlijk bezet; blokkade/reservering onderscheiden |
| Betrouwbare locatie, nul AVAILABLE, alleen `INOPERATIVE`/`OUTOFORDER` | Nog **niet** gelijkstellen aan 'allemaal bezet'; statusindicator/defect apart beoordelen | Geen vrij, maar primair defect |
| Betrouwbare locatie, nul AVAILABLE, minstens één UNKNOWN | Grijs/onzeker | Niet stellig geen vrij claimen |
| Geen operationele EVSE's | Afzonderlijk geen capaciteit; kleur/zichtbaarheid beslissen | Niet rood voor bezet |
| Feed-/locatiegegevens niet voldoende vers of completeness niet te bewijzen | Grijs met kwaliteitsreden | Niet betrouwbaar groen of rood |
| Bronupdate oud, maar valide, nieuwe onafhankelijke NDW-publicatie compleet | **Niet automatisch grijs** | Nieuwe statuswijziging is niet vereist |

**Nog te besluiten:** hoe fault-only en gemengde fault+occupied locaties in een beperkte 3-kleuren-ring getoond worden; of 'green met UNKNOWN' een gedeeltelijk/onzeker attribuut krijgt; hoe per-locatie volledigheid onafhankelijk bewezen wordt; welke responses velden `qualityReason`, `feedObservedAt`, `knownOperationalEvses`, `unknownEvses`, `isComplete` uiteindelijk nodig zijn. Namen zijn alleen ontwerpopties.

## Q5d minimale fixturematrix (nog niet uitgevoerd)

1. Eén AVAILABLE en één UNKNOWN op betrouwbaar complete locatie: positief vrij maar gedeeltelijk onbekend.
2. Geen AVAILABLE en één UNKNOWN: grijs.
3. Twee CHARGING, geen UNKNOWN: nul vrij, betrouwbare niet-vrije capaciteit.
4. Eén OUTOFORDER, geen overige EVSE: defect afzonderlijk, geen onjuiste 'allemaal bezet'.
5. Geen operationele EVSE's: geen-capaciteitcategorie.
6. Feedcheckpoint vers maar locatie incompleet: niet groen/rood op basis van onvolledige set.
7. Feedcheckpoint oud of netwerkfetch mislukt: geen actuele zekerheid.
8. Status-brontimestamp >7 dagen maar recente onafhankelijke valide publicatie: niet uitsluitend door timestamp grijs.
9. Een bij CCS2-filter geselecteerde locatie heeft extra TYPE2 EVSE's: segmenten tellen alle operationele EVSE's.
10. Herhaalde connectoren van één EVSE: capaciteit telt unieke EVSE's.

## Afbakening en volgende stap

Eerst bewijs verzamelen welke bestaande PostgreSQL-checkpoints/presencevelden daadwerkelijk lokale volledigheid en onafhankelijke feedwaarneming aantonen. Daarna ontwerpbesluiten nemen, uitvoerbare fixtures toevoegen en pas met expliciete toestemming API/Android wijzigen. Geen DB-writes, schemawijzigingen, NAS-deploy of merge. PR #114 blijft draft.
