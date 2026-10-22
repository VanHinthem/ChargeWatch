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

- Door gebruiker aangeleverde Schietspoel-extracties gecontroleerd: beide OCPI `evse_id` waarden matchen eerdere Athlon-waarden; beide status `AVAILABLE`; gedeelde `REGULAR` EUR-tariff met `ENERGY 0.2691`, 21% btw (~€0,33/kWh). Compacte brongebaseerde fixture en gerichte check gecommit.

- Op 22 oktober opnieuw de **originele twee geüploade Schietspoel-extracties** met Python gecontroleerd: locatie met twee verwachte EVSE-identificaties en twee succesvolle tariefresoluties (PASS). Ook een lokale kopie van `check_ndw_fixture.py` uitgevoerd met een uit die extracties samengestelde fixture: `Fixture check passed` (de exacte gecommitte fixture is niet rechtstreeks als bestand uitgevoerd).
- NDW HTTP HEAD vanaf agentcontainer geprobeerd, maar externe DNS-resolutie faalde. Vervolgens bevestigde de gebruiker op Windows **200 HEAD en 304 Conditional GET voor beide feeds** op 22 oktober 2026. `tools/check_ndw_http_cache.py` toegevoegd om dit vanaf Windows reproduceerbaar te testen.

## Huidige beperking

De gebruiker heeft het download-/analysescript met succes lokaal uitgevoerd. Deze agentomgeving kan de grote gzip nog steeds niet zelfstandig materialiseren:
- webfetch weigerde het Locations-bestand wegens omvang;
- Tariffs kon niet als tekst worden gedecodeerd;
- de container heeft geen externe DNS/netwerktoegang.

Dit is geen NDW-authenticatieprobleem: de landelijke downloads zijn publiek en NDW documenteert `curl ... | gunzip`.

Issue #2 is **nog niet gereed**. Basisstructuur, statusset, aantallen, ETag/Last-Modified en lokale Schietspoel-tariefresolutie zijn bewezen via het aangeleverde rapport. De Schietspoel-bronobjecten, twee EVSE-identiteiten, energietarief en tariefrelaties zijn inmiddels gecontroleerd; compacte echte fixtures plus check zijn toegevoegd. Landelijke tariefdekking en identiteit over meerdere snapshots ontbreken nog; conditionele HTTP GET met 304 is inmiddels aangetoond.

## Productbesluiten

- Naam: **ChargeWatch**; repository `VanHinthem/ChargeWatch`.
- V1: Android-app en widget, kaart/zoeken, huidige EVSE-beschikbaarheid en tarieven, alternatieven, lokale favorieten, statistieken en historische weekdag/uur-beschikbaarheidskansen.
- Geen notificaties, accounts, synchronisatie of betaalde functies in v1.
- .NET 10 ingest → PostgreSQL 18/PostGIS → read-only GET REST API → Kotlin/Compose/Glance Android.
- Landelijke compacte profielen: EVSE + locatie 168 EWMA-vakken; uptime 90 dagen per EVSE; compacte duurcategorieën. Geen onbeperkte raw events; actuele tarieven, geen tariefhistorie.

## Eerstvolgende actie

1. De conditionele HTTP-cachecheck is geslaagd op Windows: HEAD 200 en conditional GET 304 voor beide bestanden. Eventueel exacte repositoryfixture testen met `python tests/check_ndw_fixture.py`.
2. Landelijke tariefdekking en identiteitsstabiliteit over twee snapshots blijven **nog niet gemeten** (alleen bronextractie van Schietspoel beschikbaar).
3. Leg HTTP-resultaat en resterende kwaliteitsrisico's vast voordat de PR naar ready-for-review gaat.
4. **Niet mergen of deployen zonder expliciet gebruikersakkoord.**

## Open punten

- Exacte raw exportvorm en stabiele identifiers in de huidige landelijke snapshot.
- Volledige statusset/statussemantiek en datakwaliteit.
- Tariff-linkdekking en concrete prijscomponenten.
- HTTP conditional-requestgedrag en praktisch verversingsinterval.
- Licentie/attributie voor herpublicatie.
- Pas na Sprint 0: fysieke PostgreSQL-opslagvorm en benchmark.
