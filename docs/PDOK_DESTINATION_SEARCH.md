# Android destination search — PDOK Locatieserver (issue #95, fase A)

## Besluit (29 oktober 2026)

ChargeWatch werkt uitsluitend in **Nederland**. Gebruiker kiest voor **gratis adres-, postcode-, straat- en plaatszoeken**; POI's zijn geen vereiste. Gebruik **PDOK Locatieserver 3.1 rechtstreeks vanuit Android**; geen sleutel, backendproxy, extra API-endpoint, nieuwe NAS-service, database of kosten.

PDOK officiële documentatie:
- https://www.pdok.nl/pdok-locatieserver
- https://api.pdok.nl/bzk/locatieserver/search/v3_1/ui/
- https://github.com/PDOK/locatieserver
- Suggest: `https://api.pdok.nl/bzk/locatieserver/search/v3_1/suggest`
- Lookup: `https://api.pdok.nl/bzk/locatieserver/search/v3_1/lookup`

Geen publieke Nominatim; PDOK's Suggest is expliciet geschikt voor autocomplete.

## UX en contract

1. Na minimaal **2 tekens**, **250 ms debounce**, maximaal **8** suggesties (`id`, `weergavenaam`, `type`). Ondersteunde categorieën: `adres`, `postcode`, `weg`, `woonplaats`, `gemeente`. Resultaatranking komt van PDOK; de huidige kaartpositie wordt meegegeven als `lat`/`lon`-bias, niet als filter. Geen POI-belofte.
2. Bij tik op een suggestie: `lookup?id=...` en `centroide_ll` als WGS84 `POINT(lon lat)`. Geldige Nederlandse coördinaten zijn vereist; geen RD-projectie als WGS84 interpreteren.
3. De kaart zoomt naar de bestemming (14), een **blauwe bestemmingspin** staat los van laadlocaties/clusters. Wisselen light/dark en viewport-rendering behouden hem. Wissen van de zoektekst wist de bestemming.
4. De gewone ChargeWatch-viewport laadt laadmarkers zoals voorheen (ChargeWatch-default, NDW-fallback). **Geen nieuwe backendlogica voor nearest:** `GET /api/v1/locations?latitude=...&longitude=...&radiusMeters=25000&limit=5` voert PostGIS `ST_DWithin`/`ST_Distance` uit. Minder dan vijf resultaten binnen 25 km is correct.
5. Compacte lijst: operator/adres/plaats, **hemelsbrede afstand**, beschikbaarheidsratio alleen indien de feed aantoonbaar niet stale is en de EVSE-status volledig bekend is; anders `?`. Er is bewust nog geen prijs-/vermogen-enrichment voor deze lijst: de locatiezoek-API heeft geen betrouwbaar tarief-/connectoroverzicht. Bij tik: hergebruik `LocationSelectionSheet` en wacht op viewport-identiteit als die locatie nog niet geladen is; geen fictieve maplocaties maken.
6. Filters (fase B) komen in een aparte wijziging; marker- en connector-deduplicatie blijven ongewijzigd.

## Performance en privacy

- Suggestiezoekopdrachten zijn asynchroon en **cancellable tot op OkHttp-call**; vertraagde vorige zoekantwoorden mogen niet nieuwe resultaten overschrijven.
- Suggesties zijn alleen lokaal in RAM gecachet (max. 40 keys, TTL 10 minuten); cachekey houdt rekening met tekst, ruwe biasregio van 0,1 graad en limiet. Geen persistente zoekhistorie, account of persoonsprofiel.
- Suggest en lookup: TLS zonder API-key; connect timeout 3 s en call timeout 6 s. De gebruiker krijgt een neutrale foutmelding bij storing, geen verzonnen resultaten.
- Vanuit de app gaat de getypte zoektekst (en kaartcentrum-bias) **rechtstreeks naar PDOK**. Dat is een externe gegevensverstrekking en wordt in de privacyverklaring vermeld vóór publieke release.
- De ChargeWatch-nearest-call blijft naar de bestaande server gaan. De directe PDOK-geocoder is niet afhankelijk van een NAS-release. Onderzoeken of PDOK live latencies voldoende zijn op de echte Samsung blijft een acceptatiestap.

## Acceptatietest

- Probeer `Oss`, `Stationsstraat Oss`, `5341 AB`, `Schietspoel 18 Oss`, een straat zonder huisnummer en een typefout.
- Type snel, verwijder letters, wissel naar een andere bestemming tijdens loading: geen oude zoekresultaten.
- Test `suggest → lookup → pin → nearest → sheet` en terug/wissen.
- Test light/dark, zoom/cluster, favorieten en onbekende/stale feed.
- Meet op Samsung wanneer de eerste bruikbare suggesties verschijnen (niet alleen netwerkduur). Controleer gedrag bij offline, timeouts en hoge latency.
- Geen NAS-deployment, merge of productiestatus claimen voordat expliciet akkoord is gegeven.
