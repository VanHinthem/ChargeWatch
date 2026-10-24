# NDW GeoJSON Consumer — Oss contractonderzoek

Datum: 24 oktober 2026. **Status: onderzoeksopzet; nog geen live Oss-response gevalideerd.**

## Afgesproken architectuur (ongewijzigd)
Android vraagt kaartpunten rechtstreeks aan NDW. Alleen bij openen van details vraagt Android historische/statistische verrijking aan ChargeWatch; bij onbruikbare of onbereikbare NDW-responses volgt terugval naar de read-only ChargeWatch API. Favorieten en voorkeuren blijven lokaal. Play Integrity plus server-side controle is verplicht vóór publieke API-uitrol.

## Voorlopige aannames (geen geverifieerde NDW-garanties)
- **Gedocumenteerd:** GeoJSON bbox maximaal 1,0 vierkante graad, tot 1.000 features, 10 requests/s, HTTP 429 bij overschrijding (zie NDW Consumer Pull-documentatie).
- **Werkhypothese:** 10 requests/s wordt per **publiek bron-IP-adres** afgedwongen; mobiele clients achter NAT/CGNAT of gedeeld wifi-netwerk **delen** dan een quotum. Het is **niet** bevestigd door NDW en niet gegarandeerd per persoon of app-installatie. Vraag NDW om schriftelijke duidelijkheid vóór publieke release.
- Voor de mobiele implementatie plannen we defensief maximaal één gelijktijdig kaartverzoek, debounce (bijv. 500 ms), request-cancel bij viewportwissel, korte viewport-cache (bijv. 60 s) en geen automatische herhaling van 429. Deze waarden zijn **MVP-configuratievoorstellen**, niet NDW-verplichtingen.
- Bij 429: Retry-After respecteren indien gegeven, anders conservatieve backoff; daarna zo nodig beperkte fallback. Ook onze fallback-API heeft capaciteitslimieten. Niet bij kaart-pan/zoom automatisch statistieken ophalen.

## Reproduceerbare Oss-proef

Python 3.10+ (standaardbibliotheek), vanaf repository-root:

```powershell
python tools/ndw_geojson_oss.py
```

Standaard bbox: `5.49,51.74,5.56,51.80`, oppervlakte 0,0042 vierkante graad. Output: `data/ndw-geojson-oss/report.json` (lokaal, niet committen). Eén GET, geen login/token, geen retries. Andere bbox kan via `--bbox`, mits valide en maximaal 1 vierkante graad.

Offline inspectie van zelf opgeslagen GeoJSON:

```powershell
python tools/ndw_geojson_oss.py --input data/sample.geojson --output data/ndw-geojson-oss/offline.json
```

Bij 1.000 features of meer stopt de tool met een fout wegens mogelijk afgekapt kaartresultaat. De proef toont gevonden top-level propertynamen, availability-propertynamen, aantallen/duplicaten en maximaal tien voorbeelden met ruwe `feature.id`, `cpo_id`, `country`, coördinaten, `last_updated` en `availabilities`. Geen afleiding van statusbetekenis uit `total - available` alsof alle overige aansluitingen laden.

## OCPI-mapping: harde acceptatiegate

Onze bestaande importer slaat `charging_location(country_code, party_id, source_id)` op (unieke combinatie), EVSE's afzonderlijk via `evse(location_id, source_uid)`. Het huidige API-detail/statistiekcontract gebruikt daarentegen een interne numerieke `location.id`.

1. Bekijk de ruwe GeoJSON feature-IDs en properties van minimaal 5–10 echte Oss-locaties. Controleer welke velden daadwerkelijk OCPI Location `country_code`, `party_id` en `id` representeren. **Niet** op basis van alleen coördinaten/adres koppelen, niet aannemen dat `feature.id` veilig op koppeltekens gesplitst kan worden.
2. Vergelijk kandidaten met de bestaande lokale/NAS `charging_location` database via read-only SQL, of met de huidige `GET /api/v1/locations?q=Oss` (let op resultlimiet). Leg exacte match, geen match, meerdere matches en gewijzigde bron-ID vast.
3. Vergelijk actuele `availabilities` met de individuele EVSE-statussen uit `GET /api/v1/locations/{id}` zonder semantisch gelijkheid van aggregaten te veronderstellen. Controleer timestamp en operatorvelden.
4. Controleer of GeoJSON `tariff_ids` bevat en of er individualiseerbare EVSE/connector-details aanwezig zijn. Geen nationale tariefdownloads op mobiele kaartverzoeken.
5. Bevestig NDW rate-limit scope, cache-/hergebruiksvoorwaarden en eventuele feature-detail lookup extern. Zonder bevestiging blijft bovenstaand een aanname.

**Resultaat nog open:** daadwerkelijke HTTP-code, Oss feature count, response headers, geobserveerde velden, live ID-matches, statusvergelijking en tijdstempels. De agentomgeving kon het dynamische endpoint niet bereikbaar testen. Een geslaagde offline parsercheck alleen is **niet** genoeg om de identity-gate te sluiten.

## Vervolg na geslaagde meting
Een kleine Android NDW-first kaartclient met fixtures; daarna een read-only serverendpoint voor gericht detail/statistiek op bewezen externe OCPI-location-key (zonder interne DB-ID aan mobiele client te koppelen). Onafhankelijk daarvan releasegates uit `docs/ANDROID_NDW_SECURITY_TESTPLAN.md`. Geen publiek exposure, NAS deployment of merge zonder expliciet gebruikersakkoord.
