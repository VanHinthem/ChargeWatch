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

## Eerste echte Oss-meting — 24 oktober 2026 (aangeleverd rapport)

De gebruiker heeft `tools/ndw_geojson_oss.py` uitgevoerd met de standaardbbox `5.49,51.74,5.56,51.80` en het resulterende rapport gedeeld. Dit is **wel** een daadwerkelijk gegenereerd NDW-rapport, maar de tool bewaart geen ruwe HTTP-status/headers en slechts maximaal tien feature-samples, niet alle 252 ruwe features.

- `type=FeatureCollection`, `feature_count=252`. Geen ontbrekende feature-ID's, geen dubbele feature-ID's, geen ongeldige features.
- Geobserveerde properties: `address`, `availabilities`, `country`, `cpo_id`, `last_updated`, `open`, `operator_name`, `owner_name`, `suboperator_name`.
- Geobserveerde availability-properties: `available`, `total`, `connector_type`, `connector_format`, `power_type`, `power_max`, `tariff_ids`. Geen individuele EVSE-status of EVSE-UID in het rapport.
- Echte voorbeelden: `NL-505-91056291` / 50five / Schutterij 32 / 1 van 1 vrij; `NL-NUO-402fa3d671aef15471b2f7a47f2d12e7` / Vattenfall / Arena 2 / 0 van 2 vrij; `NL-EFL-650dc75ff063f600200cc198` / E-Flux / Lekstraat 13 / 1 van 2 vrij.
- Eén werkelijk feature-ID kan meerdere koppeltekens bevatten (bijv. UUID); niet naïef `split("-")` gebruiken. Kandidaatformaat `<country>-<cpo_id>-<rest>` is een **hypothese**, niet voldoende voor gevalideerde OCPI-mapping.
- Rapportvoorbeeld toont `country=NLD` terwijl de feature-ID begint met `NL`; ook `country` kan dus niet klakkeloos aan OCPI `country_code` worden gekoppeld.
- `last_updated` is aanwezig, maar de semantiek (wijziging/poll) is hiermee niet vastgesteld. Geen gevolgtrekking over de verdeling van alle 252 locatie-/statusrecords op grond van de tien voorbeelden.
- **Identity gate blijft open**: geen directe vergelijking met werkelijke `charging_location(country_code,party_id,source_id)` uit de NAS/Postgres of API gedaan. NDW rate-limit scope per publiek IP eveneens onbevestigd.

**Volgende gerichte verificatie:** read-only lookup van dezelfde tien kandidaten in de lokale/NAS-OCPI-tabel en individuele EVSE/connectorgegevens; vastleggen van exacte matches en mismatches. Geen backend- of Android-code op grond van onbewezen identity-contract implementeren.

## Concrete databasevergelijking beschikbaar

`tools/ndw_geojson_oss_match.sql` bevat de tien geobserveerde NDW-samples uit het Oss-rapport. Het draait in `BEGIN TRANSACTION READ ONLY` en vraagt:

- mogelijke OCPI identity candidate `NL + cpo_id + remainder(feature.id na "NL-<cpo_id>-")`;
- database-locaties binnen 30 meter, **alleen als extra vergelijkingskandidaten**, nooit bewijs van dezelfde locatie;
- bron-ID, naam/adres, afstand, statusverdeling en het verschil tussen geaggregeerde NDW-`available/total` en individuele EVSE-statussen.

Run vanaf Windows PowerShell, wanneer NAS SSH en Docker beschikbaar zijn (pas hostname en gebruikersnaam aan):

```powershell
Get-Content -Raw tools/ndw_geojson_oss_match.sql | ssh hinthem@HINNAS "cd /volume2/docker/chargewatch && docker compose exec -T db psql -U chargewatch_owner -d chargewatch"
```

**Let op:** `chargewatch_owner` in dit voorbeeld is alleen de bestaande administratieve connectie; de SQL-transactie is expliciet read-only. Gebruik bij voorkeur een werkende read-only DB-rol als lokale authenticatie dat toestaat. Controleer de daadwerkelijke Compose-DB-naam/credentials; stuur nooit een wachtwoord of connection string naar Git. De opdracht gebruikt geen wijzigingen aan de collector of database. Alleen als `docker compose` op die NAS toestemming vereist, moet de beheerder de gebruikelijke `sudo`-werkwijze volgen.

**Verificatiecriteria:** iedere van de tien features krijgt nul, één of meerdere kandidaten met gedocumenteerde reden; accepteer externe sleutelmapping pas na 1-op-1 bevestiging en controle van afwijkingen. Een gecombineerde locatie-/EVSE-statusteller is niet noodzakelijk numeriek gelijk aan NDW connector-aggregaties.

## NAS-vergelijking uitgevoerd — 24 oktober 2026

De gebruiker heeft `tools/ndw_geojson_oss_match.sql` op HINNAS tegen de bestaande ChargeWatch-catalogus uitgevoerd. De read-only transactie gaf `BEGIN`, **12 rijen**, `COMMIT`, zonder gemelde SQL-fout.

**Resultaat over de 10 verschillende NDW-features:** 10/10 hebben exact één kandidaat met `id_exact_candidate=t` op `country_code=NL`, `party_id=cpo_id`, `source_id=feature.id` na de prefix `NL-<cpo_id>-`. Alle tien exacte kandidaten hebben gelijk adres; afstand 0,0–0,2 meter. Alle tien hebben dezelfde telling `reported_available=available_evses` en `reported_total=evse_count` in deze momentopname. Dit is geen bewijs dat NDW-connectortellingen algemeen altijd EVSE-tellingen zijn.

**Waarom 12 in plaats van 10 rijen:** Sportlaan 31 en Podium 1 liggen 4,2–4,4 meter van elkaar; de extra nabijheidskandidaten hebben `id_exact_candidate=f` en `address_equal=f`. Ruimtelijke nabijheid is dus nadrukkelijk ongeschikt als identiteitssleutel.

**Statusvoorbeeld:** Merwedestraat 54 heeft NDW `available=0,total=1`, terwijl de catalogus `INOPERATIVE` toont: `total-available` is niet hetzelfde als `CHARGING`.

**Besluit binnen de onderzoekscope:** de mapping is empirisch ondersteund voor deze tien echte Oss-locaties. Android mag een conservatieve parser voor `NL-<cpo_id>-<source_id>` gebruiken, maar moet prefix en niet-lege delen valideren, ongeldige IDs veilig afwijzen en nooit via coördinaten/adres automatisch verrijken. Exact NDW-contract, cross-operator-dekking en stabiliteit over tijd blijven buiten deze steekproef; niet als universele zekerheid presenteren.

**Nog open:** rate-limit-scope (per publiek IP is aanname), HTTP-responseheaders, toekomstig 1000-features-gedrag, volledige tariefsemantiek, tijdstempelbetekenis en publieke externe-key endpoint. Geen DB/schema/code-releasewijziging gedaan.

## Vervolg na geslaagde meting
Een kleine Android NDW-first kaartclient met fixtures; daarna een read-only serverendpoint voor gericht detail/statistiek op bewezen externe OCPI-location-key (zonder interne DB-ID aan mobiele client te koppelen). Onafhankelijk daarvan releasegates uit `docs/ANDROID_NDW_SECURITY_TESTPLAN.md`. Geen publiek exposure, NAS deployment of merge zonder expliciet gebruikersakkoord.
