# ChargeWatch — actuele werkstand

**Bijgewerkt:** 22 oktober 2026
**Status:** ontwerpfase; nog geen applicatiecode. Dit document is een overdrachtsnotitie, geen bewijs van actuele GitHub-/CI-status.

## Focus
Een pragmatische, agent-gestuurde werkwijze inrichten. **Huidig voorstel in review:** branch `docs/agent-workflow-handoff` met AGENTS.md, WORKFLOW.md, STATUS.md en CHAT_START.md. **Niet mergen zonder gebruikersvalidatie.**

## Productbesluiten
- Naam: **ChargeWatch**; repository `VanHinthem/ChargeWatch`.
- V1: Android-app en widget, kaart/zoeken, huidige EVSE-beschikbaarheid en tarieven, alternatieven, lokale favorieten, statistieken en historische weekdag/uur-beschikbaarheidskansen.
- Geen notificaties, accounts, synchronisatie of betaalde functies in v1; optioneel latere aparte gebruikersdatabase.
- .NET 10 ingest → PostgreSQL 18/PostGIS → read-only GET REST API → Kotlin/Compose/Glance Android.
- Landelijke compacte profielen: EVSE + locatie 168 EWMA-vakken; uptime 90 dagen per EVSE; duurcategorieën voor vrij/niet-vrij/storing per EVSE en perioden zonder vrije aansluitingen per locatie. Geen onbeperkte raw events; actuele tarieven, geen tariefhistorie.
- Detail: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [ROADMAP](ROADMAP.md), [ALERTS](ALERTS.md) (notificaties uitgesteld).

## Onderzoeksbewijs
- Gebruiker vergeleek Schietspoel 18 in Oss via Athlon/Deftpower en NDW GeoJSON: op één tijdstip 2/2 AVAILABLE, corresponderende locatie/EVSE-gegevens. Dit **valideert nog niet** de landelijke OCPI-detailstructuur, statussemantiek of tarieven.
- NDW landelijke OCPI Locations en Tariffs moeten nog met echte bestanden worden onderzocht.
- Geen geverifieerde landelijk telling of PostgreSQL-opslagbenchmark. Grootteramingen in architectuur zijn hypothetisch.

## Eerstvolgende feature na goedkeuring werkwijze
**Sprint 0 — NDW data discovery en schema-input** (zie ROADMAP).
Doel: echte OCPI Locations/Tariffs-structuur en EVSE-statuscodes valideren, Schietspoel 18 vergelijken, IDs/versheid en datakwaliteit toetsen, impact op compact gegevensmodel beschrijven.
Acceptatie: reproduceerbare voorbeelddata/fixtures zonder credentials, concrete veldmapping en lijst met onbekenden. Daarna apart schema/performance-werkblok; niet alvast de hele backend bouwen.

## Overdracht aan volgende agent
1. Lees AGENTS.md en WORKFLOW.md, dan ROADMAP/ARCHITECTURE/PRODUCT.
2. **Controleer eerst live op GitHub** of de workflow-PR open of gemerged is, en of er nieuwe issues/PR's of commits zijn.
3. Vraag de gebruiker om review van de workflow-PR als deze nog openstaat. Niet zelfstandig mergen.
4. Na akkoord: kies Sprint 0 als eerste feature, leg grenzen vast, maak een nieuwe featurebranch, werk en bied PR met checks aan.
5. Werk deze STATUS bij met concrete issue-/PR-URL's, branch, laatste checks, open blockers en volgende actie. Houd claims van uitvoering gescheiden van plannen.

## Open punten
- Review/goedkeuring van deze agentworkflow.
- Welke precieze lokale ontwikkel- en CI-commando's beschikbaar zijn zodra code bestaat.
- NDW OCPI-staalname, stabiele identifiers, statusmapping, importfrequentie.
- Database-opslagbenchmark en quality thresholds voor statistieken.
