# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026
**Status:** Sprint 0 in uitvoering op `feature/ndw-data-discovery`; issue #2, draft PR #3. Workflow-PR #1 is gemerged. Geen CI-workflows/statuschecks aanwezig.

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
- `tools/ndw_discover.py` toegevoegd: Python 3.10+ standaardbibliotheek, publieke gzip-downloads, bronmetadata/SHA256, EVSE-statusfrequenties, Schietspoel-selectie en voorlopige tariefresolutie. Gebruik: `python tools/ndw_discover.py` (zie `tools/README.md`). Ruwe resultaten onder `data/ndw-discovery/` worden genegeerd door Git.
- **Gebruikersrun uitgevoerd op echte NDW-gzip (22 oktober 2026):** beide root-typen zijn JSON-arrays; 80.777 locaties, 203.114 EVSE's, 203.807 connectoren, 101.209 tarieven en 9 verschillende statuswaarden. Eén Schietspoel-locatie gevonden, twee connectoren verwijzen naar één daadwerkelijk geresolveerd tarief. Zie [snapshotrapport](NDW_SNAPSHOT_2026-10-22.md). Bronobjecten zelf zijn nog niet aangeleverd; geen raw fixtures.

## Huidige beperking

De gebruiker heeft het download-/analysescript met succes lokaal uitgevoerd. Deze agentomgeving kan de grote gzip nog steeds niet zelfstandig materialiseren:
- webfetch weigerde het Locations-bestand wegens omvang;
- Tariffs kon niet als tekst worden gedecodeerd;
- de container heeft geen externe DNS/netwerktoegang.

Dit is geen NDW-authenticatieprobleem: de landelijke downloads zijn publiek en NDW documenteert `curl ... | gunzip`.

Issue #2 is **nog niet gereed**. Basisstructuur, statusset, aantallen, ETag/Last-Modified en lokale Schietspoel-tariefresolutie zijn bewezen via het aangeleverde rapport. De daadwerkelijke bronobjecten, prijscomponenten, conditionele HTTP-responses, identiteit over meerdere snapshots en echte fixtures ontbreken nog.

## Productbesluiten

- Naam: **ChargeWatch**; repository `VanHinthem/ChargeWatch`.
- V1: Android-app en widget, kaart/zoeken, huidige EVSE-beschikbaarheid en tarieven, alternatieven, lokale favorieten, statistieken en historische weekdag/uur-beschikbaarheidskansen.
- Geen notificaties, accounts, synchronisatie of betaalde functies in v1.
- .NET 10 ingest → PostgreSQL 18/PostGIS → read-only GET REST API → Kotlin/Compose/Glance Android.
- Landelijke compacte profielen: EVSE + locatie 168 EWMA-vakken; uptime 90 dagen per EVSE; compacte duurcategorieën. Geen onbeperkte raw events; actuele tarieven, geen tariefhistorie.

## Eerstvolgende actie

1. Inspecteer de lokaal al gegenereerde `data/ndw-discovery/schietspoel_locations.json` en `schietspoel_tariffs.json` uit dezelfde snapshot; check EVSE-ID-mapping en tariefcomponenten.
2. Voeg kleine, echte fixtures en een gerichte herhaalbare validatiecheck toe.
3. Verifieer globale tariefkoppeling, HTTP-conditional requests en identiteit over snapshots waar haalbaar.
4. Werk mapping/onbekenden bij en bied issue #2 daarna als afgeronde PR aan voor gebruikersvalidatie.
5. **Niet mergen of deployen zonder expliciet gebruikersakkoord.**

## Open punten

- Exacte raw exportvorm en stabiele identifiers in de huidige landelijke snapshot.
- Volledige statusset/statussemantiek en datakwaliteit.
- Tariff-linkdekking en concrete prijscomponenten.
- HTTP conditional-requestgedrag en praktisch verversingsinterval.
- Licentie/attributie voor herpublicatie.
- Pas na Sprint 0: fysieke PostgreSQL-opslagvorm en benchmark.
