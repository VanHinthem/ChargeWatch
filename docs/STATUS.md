# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026  
**Status:** Sprint 0 afgerond en gemerged; volgende stap is Sprint 1 afbakenen. Nog geen applicatiecode, database of deployment.

## GitHub-status (gecontroleerd op 22 oktober 2026)

- `main`: Sprint-0-squashmerge `5b88c62b1e14010d4727242d9eaa3d69d0343177`.
- [PR #3](https://github.com/VanHinthem/ChargeWatch/pull/3): **gemerged** na expliciet gebruikersakkoord.
- [Issue #2](https://github.com/VanHinthem/ChargeWatch/issues/2): **afgesloten**.
- Op het controlemoment geen open issues. Voor start van elk werkblok opnieuw live controleren.
- Geen GitHub Actions-runs of commit-statuschecks aangetroffen bij Sprint-0-review; er was **geen groene CI** te claimen.
- Deze statuscorrectie wordt aangeboden via branch `docs/status-after-sprint-0`; geen directe commit naar `main`.

## Sprint 0 — afgerond: NDW data discovery

Landelijke NDW OCPI Locations- en Tariffs-gzip daadwerkelijk gedownload en geanalyseerd op Windows. De JSON-root is een array voor beide bronnen.

| Gemeten op 22 oktober 2026 | Aantal |
|---|---:|
| Locaties | 80.777 |
| EVSE's | 203.114 |
| Connectoren | 203.807 |
| Tariefobjecten | 101.209 |

- Negen EVSE-statuswaarden aangetroffen, waaronder `AVAILABLE`, `CHARGING`, `UNKNOWN`, `REMOVED`, `PLANNED`, `INOPERATIVE` en `OUTOFORDER`.
- Bij Schietspoel 18 in Oss twee EVSE's gevalideerd. `uid` is een UUID; `evse_id` bevat de externe `NL*NUO*...`-identifier. Beide `AVAILABLE` in onderzochte snapshot; elk Type 2 AC 11,04 kW.
- Tarief voor beide connectoren succesvol geresolveerd: `NL/NUO/2000025394-2000011292`; `ENERGY` €0,2691 exclusief 21% btw, rekenkundig circa €0,33/kWh inclusief btw. Contractspecifieke laadpastarieven kunnen afwijken.
- HTTP `HEAD 200` en conditionele `GET 304` voor beide datasets bevestigd op Windows. `ETag` / `Last-Modified` bruikbaar; nieuw gzip-bestand bij gewijzigde ETag nog steeds downloaden.
- Twee landelijke snapshots vergeleken: alle 80.777 locatiekeys en 203.114 EVSE-keys gedeeld; geen gewijzigde externe `evse_id` bij gedeelde keys; geen dubbele samengestelde locatie-/EVSE-/tariefkeys.
- 24.566 connectoren (12,05%) zonder `tariff_ids`. Van 179.241 tariefverwijzingen zijn 174.161 (97,17%) exact geresolveerd en 5.080 (2,83%) niet. **Onbekend tarief is nooit prijs nul.**
- Brongebaseerde ingekorte fixture, Python-onderzoeks- en controlescripts en reproduceerbare instructies op `main`. Schietspoel/rapportanalyses bevestigd door gebruikersrun; niet alle tests zijn als CI uitgevoerd.

Documentatie:
- [NDW discovery](NDW_DATA_DISCOVERY.md)
- [Nationale snapshot](NDW_SNAPSHOT_2026-10-22.md)
- [Nationale vergelijking](NDW_COMPARISON_2026-10-22.md)
- [Toolinstructies](../tools/README.md)

## Product- en architectuurbesluiten

- V1: landelijke laadlocaties, EVSE-status/versheid, actuele tarieven, kaart/zoeken, alternatieven, lokale Android-favorieten/widget en compacte historische beschikbaarheidsstatistieken.
- Geen notificaties, accounts, persoonlijke serversynchronisatie of betalingen in v1.
- .NET 10 importer (schrijft) → PostgreSQL 18/PostGIS → afzonderlijke read-only databasegebruiker en GET-only REST API → Kotlin/Compose/Glance Android.
- Landelijke 168-vaks weekdag/uur-profielen per EVSE én locatie; 90-daagse EVSE-uptime, compacte duurprofielen. Geen onbeperkte raw event-/tariefhistorie.
- Samengestelde locatie-/tariefbronkey: `country_code + party_id + id`; EVSE-key onder locatie + `uid`; externe `evse_id` apart bewaren.

## Eerstvolgende stap — Sprint 1 (nog niet gestart)

**Voorstel:** een beheersbaar eerste technisch werkblok voor het datamodel en importfundament, af te bakenen in een nieuw issue vóór implementatie.

Inhoud om als één samenhangende scope te beoordelen:
1. .NET 10-solution en importprojectbasis.
2. PostgreSQL 18 + PostGIS ontwikkelomgeving via Docker Compose, gescheiden import-/API-rollen.
3. Eerste schema voor Location, EVSE, Connector, Tariff en ImportRun met unieke bronkeys.
4. Validatie/staging en beperkte echte proefimport inclusief datakwaliteitsmetingen en daadwerkelijke opslag-/importbenchmark.
5. Gerichte tests voor dubbele keys, ontbrekende tarieven, incomplete snapshots en idempotentie.

**Niet in dit werkblok:** Android, REST-API-features, complete langdurige statistiekpipeline of productie-deployment. Schema-/performancekeuzes toetsen aan de nationale dataset vóór definitieve vastlegging.

## Open risico's / beslissingen

- NDW-licentie, attributie en voorwaarden voor herpublicatie vóór publieke distributie expliciet vaststellen.
- Twee snapshots tonen geen langetermijnstabiliteit van bronkeys; wijzigingen en bronkwaliteit bij iedere import bewaken.
- Statusversheid en semantiek zorgvuldig behandelen: geaggregeerde aantalsverschillen vormen geen individuele tijdreeks; ontbrekende/incomplete import nooit stilzwijgend omzetten in verwijdering of storing.
- Werkelijke databaseomvang, index-/WAL-kosten en opslagvorm van landelijke statistieken meten; eerdere ramingen zijn geen benchmark.
- Bepalen van precieze eerste database- en importcontracten binnen Sprint 1.

## Overdracht aan volgende agent

1. Lees `AGENTS.md`, deze status, `WORKFLOW.md`, `ROADMAP.md`, `PRODUCT.md`, `ARCHITECTURE.md` en de drie NDW-rapporten.
2. Controleer live GitHub issues, branches, PR's en CI; neem deze status niet als bewijs voor later.
3. Als statuscorrectie-PR nog open is: vraag expliciete mergegoedkeuring, geen automatische merge.
4. Start daarna één afgebakend Sprint-1-issue en featurebranch; update dit document op die branch.
5. **Nooit merge of deploy zonder expliciete gebruikersgoedkeuring.**
