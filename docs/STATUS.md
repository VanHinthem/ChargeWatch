# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026  
**Status:** Sprint 0 en 1 gemerged, geen deployment. Sprint 2 nog niet gestart.

## Afgeronde sprints

- Sprint 0: [PR #3](https://github.com/VanHinthem/ChargeWatch/pull/3), issue #2 gesloten. NDW OCPI-broncontract onderzocht; beide gzip-feeds met HTTP 304 bevestigd. Zie [brononderzoek](NDW_DATA_DISCOVERY.md), [snapshot](NDW_SNAPSHOT_2026-10-22.md) en [vergelijking](NDW_COMPARISON_2026-10-22.md).
- Sprint 1: [PR #6](https://github.com/VanHinthem/ChargeWatch/pull/6), issue #5 gesloten; squashmerge `1c470982620c533ba3ca687df918fcda0345d469`. .NET 10 NDW-importer, PostgreSQL 18/PostGIS-schema, gescheiden import- en leesrol, transactionele COPY/staging-bulkimport, bescherming tegen ongewijzigde updates. Geen productie- of NAS-deployment.
- De eerdere workflow-PR #1 en status-PR #4 zijn ook gemerged.
- Deze wijziging in `docs/status-workflow-update` corrigeert de status op main en legt de toekomstige mergeklare statusoverdracht vast. De actuele PR/CI-status moet live gecontroleerd worden.

## Vastgestelde NDW-data

Snapshot van 22 oktober 2026: **80.777 locaties, 203.114 EVSE's, 203.807 connectoren en 101.209 tarieven**. Locatie- en tariefkey = `country_code + party_id + id`; EVSE-key = locatie + `uid`, extern `evse_id` apart. Van 179.241 tariefverwijzingen werden 174.161 geresolveerd en 5.080 niet; 24.566 connectoren hebben geen tariefverwijzing. Onbekend tarief is niet €0.

## Sprint 1 — werkelijk uitgevoerde controles

Gebruiker bevestigde werkende .NET 10-build, PostgreSQL/PostGIS-initialisatie, Schietspoel-fixture, herhaalbaarheid, rollback bij onvolledige input en SELECT-only-rechten van `chargewatch_read`. De landelijke catalogusaantallen matchen de NDW-bron.

Schone benchmarkdatabase `chargewatch_benchmark`, originele ontwikkelcatalogus intact:
- Leeg vóór import: **16.430.783 bytes**.
- Eerste landelijke bulkimport: **28,100 s**, daarna **224.024.255 bytes**.
- Tweede identieke landelijke bulkimport: **27,167 s**, exitcode 0, daarna **224.147.135 bytes**.
- Extra databaseopslag bij herimport: **122.880 bytes** (~0,055%). Beide runs succesvol; aantallen identiek en 2 `completed`-runs.
- Oudere rij-voor-rij-import: **828,496 s** (andere uitgangssituatie).
- Zie [Sprint-1-instructies](SPRINT1_IMPORT.md). Er is geen CI-workflow ingericht; claim geen groene CI.

## Richting en volgende actie

V1: landelijke actuele laadstatus/tarieven, kaart/zoeken, alternatieven, lokale Android-favorieten en widget, compacte beschikbaarheidsstatistieken. .NET 10 importer → PostgreSQL 18/PostGIS → read-only GET API → Kotlin/Compose/Glance. Geen accounts, push/notificaties of betalingen in v1.

**Sprint 2 nog af te bakenen:** voorstel is automatische NDW-verversing met ETag, scheduler, foutafhandeling, stale-detectie en veiligheidsgrenzen. Maak daarvoor een issue en featurebranch; geen Android/API/statistiekpipeline in dezelfde scope.

Open punten: herpublicatielicentie/attributie NDW, langdurige bronidentiteit en datakwaliteit, volledige wijzigingsscenario's, ontbrekende records, CI en toekomstige statistiekverwerking. Lokale databases/volumes niet zonder toestemming verwijderen.

## Overdracht / vaste werkwijze

Lees `AGENTS.md`, `docs/WORKFLOW.md`, deze status, ROADMAP, PRODUCT en ARCHITECTURE. Controleer GitHub-issues, PR's, branches en CI opnieuw. Werk `docs/STATUS.md` **voor het definitieve mergeverzoek** op dezelfde featurebranch bij met afgeronde resultaten, checks, beperkingen en volgende actie. Status wordt met de goedgekeurde feature-PR naar main gemerged; na merge verifiëren, niet standaard een extra status-PR. Merge en deployment vereisen afzonderlijke expliciete goedkeuring.
