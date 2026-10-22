# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026
**Status:** Sprint 0 in uitvoering op `feature/ndw-data-discovery`; issue #2. Workflow-PR #1 is gemerged. Geen CI-workflows/statuschecks aanwezig.

## Focus

**Sprint 0 — NDW data discovery en schema-input.**

Doel: de echte landelijke NDW/DOT-NL OCPI Locations- en Tariffs-feeds valideren voordat database- of importercontracten worden vastgelegd.

Actieve werkitems:
- Issue #2: `Sprint 0: validate NDW OCPI locations and tariffs data contract`
- Branch: `feature/ndw-data-discovery`
- Document: [NDW_DATA_DISCOVERY.md](NDW_DATA_DISCOVERY.md)

## Actueel bereikt

- Live GitHub gecontroleerd: geen andere open issues of PR's bij start; workflow-PR #1 is op 22 oktober 2026 gemerged naar `main`.
- Geen GitHub Actions-runs of commit-statuschecks gevonden voor de huidige main-head.
- NDW Open Data en laadpuntdocumentatie live gecontroleerd.
- Bevestigd: landelijke OCPI Locations- en Tariffs-gzip, OCPI 2.2.1 aanlevercontract, NDW/AFIR-verplichte velden en expliciete waarschuwing dat laadpunt-API/specs nog kunnen wijzigen.
- Publicatiemetadata op 22 oktober 2026 vastgelegd: Locations circa 17 MB, Tariffs circa 4.0 MB, met updates op dezelfde dag.
- Voorlopige ChargeWatch-veldmapping en Schietspoel-zoekankers gedocumenteerd in `docs/NDW_DATA_DISCOVERY.md`.
- Bewust geen synthetische raw fixtures toegevoegd.

## Huidige blokkade

De uitvoeringsomgeving van deze agent kon de 17 MB gzip niet materialiseren:
- webfetch weigerde het Locations-bestand wegens omvang;
- Tariffs kon niet als tekst worden gedecodeerd;
- de container heeft geen externe DNS/netwerktoegang.

Dit is geen NDW-authenticatieprobleem: de landelijke downloads zijn publiek en NDW documenteert `curl ... | gunzip`.

Daarom is issue #2 **nog niet gereed**. Raw payloadstructuur, exacte Schietspoel-records, tariff-resolutie, statusfrequenties, HTTP cachingmetadata en echte fixtures moeten nog worden vastgesteld.

## Productbesluiten

- Naam: **ChargeWatch**; repository `VanHinthem/ChargeWatch`.
- V1: Android-app en widget, kaart/zoeken, huidige EVSE-beschikbaarheid en tarieven, alternatieven, lokale favorieten, statistieken en historische weekdag/uur-beschikbaarheidskansen.
- Geen notificaties, accounts, synchronisatie of betaalde functies in v1.
- .NET 10 ingest → PostgreSQL 18/PostGIS → read-only GET REST API → Kotlin/Compose/Glance Android.
- Landelijke compacte profielen: EVSE + locatie 168 EWMA-vakken; uptime 90 dagen per EVSE; compacte duurcategorieën. Geen onbeperkte raw events; actuele tarieven, geen tariefhistorie.

## Eerstvolgende actie

1. Download in een omgeving met directe HTTP-toegang:
   - `charging_point_locations_ocpi.json.gz`
   - `charging_point_tariffs_ocpi.json.gz`
2. Inspecteer top-level vorm en aantallen.
3. Extract Schietspoel 18, beide EVSE's en gekoppelde tariffs uit **dezelfde snapshot**.
4. Voeg kleine echte fixtures + compacte reproduceerbare analysecheck toe.
5. Werk mapping/onbekenden bij en bied issue #2 daarna als afgeronde PR aan voor gebruikersvalidatie.
6. **Niet mergen of deployen zonder expliciet gebruikersakkoord.**

## Open punten

- Exacte raw exportvorm en stabiele identifiers in de huidige landelijke snapshot.
- Volledige statusset/statussemantiek en datakwaliteit.
- Tariff-linkdekking en concrete prijscomponenten.
- HTTP conditional-requestgedrag en praktisch verversingsinterval.
- Licentie/attributie voor herpublicatie.
- Pas na Sprint 0: fysieke PostgreSQL-opslagvorm en benchmark.
