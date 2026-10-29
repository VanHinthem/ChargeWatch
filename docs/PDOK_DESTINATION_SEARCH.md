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
3. Na `lookup` verschijnt de **blauwe bestemmingspin** (onafhankelijk van laadmarkers/clusters); een voorlopige adresweergave (zoom 14) houdt de kaart responsief tijdens het ophalen van de nabije locaties. Zodra de nearest-query is afgerond, berekent MapLibre **éénmalig een dynamisch overzicht** dat de blauwe pin en alle gevonden (maximaal 5) laadlocaties probeert te omvatten. De berekening gebruikt de WGS84-coördinaten en **asymmetrische padding bovenin** voor de uitgeklapte zoeklijst, met marge rond de markers. De uiteindelijke zoom is begrensd op **11,0–16,5**, zodat een uitzonderlijk verre vijfde locatie niet de hele kaart onbruikbaar klein maakt (in dat geval kan die buiten de begrensde uitsnede vallen). Bij nul gevonden laadlocaties of onbruikbare/identieke coördinaten blijft het fallback-overzicht rond het adres op zoom 14. De camera wordt **niet opnieuw automatisch ingepast** bij in-/uitklappen, handmatig slepen/zoomen of normale kaartupdates. Light/dark en viewport-rendering behouden de bestemmingspin; wissen verwijdert hem.
4. De gewone ChargeWatch-viewport laadt laadmarkers zoals voorheen (ChargeWatch-default, NDW-fallback). **Geen nieuwe backendlogica voor nearest:** `GET /api/v1/locations?latitude=...&longitude=...&radiusMeters=25000&limit=5` voert PostGIS `ST_DWithin`/`ST_Distance` uit. Minder dan vijf resultaten binnen 25 km is correct.
5. **In-/uitschuifbare lijst direct onder de zoekbalk**, met een blijvend zichtbare, compacte kop (bijvoorbeeld **`5 dichtstbijzijnde locaties`**, pijl) en een geanimeerde, scrollbare inhoud. Tik op de kop of swipe **omhoog/omlaag op de kop** om in/uit te klappen; bij nieuwe bestemming opent de lijst automatisch, bij selectie van een laadlocatie klapt hij in. Er vindt geen extra nearest-query plaats bij in-/uitschuiven. Rijen tonen operator/adres/plaats, **hemelsbrede afstand**, beschikbaarheidsratio alleen indien de feed aantoonbaar niet stale is en de EVSE-status volledig bekend is; anders `?`. Een reeds als favoriet opgeslagen laadlocatie krijgt in de rij dezelfde **goudgele ster** als elders in Android, op basis van de bestaande OCPI-`featureId`; bij andere locaties staat **geen** ster, en de ster is hier alleen een visuele aanduiding (de rij blijft een kaartnavigatieactie). Geen prijs-/vermogen-enrichment: de locatiezoek-API levert daarvoor nog geen betrouwbaar lijstcontract.
6. Tik op een nabije laadlocatie **verplaatst/zoomt alleen de kaart** naar die laadlocatie (zoom 17); **de compacte location sheet wordt niet automatisch geopend**. De gebruiker kan handmatig op de laadmarker/cluster tikken om de bestaande `LocationSelectionSheet` te openen. Tijdens een actieve detailsheet verbergen we de nearby-kop en -lijst tijdelijk om overlap te voorkomen.
7. De **blauwe bestemmingspin blijft staan** tijdens kaartbeweging, selectie van nabije laadlocaties, bewerken van een niet-lege zoekopdracht en tabwissels binnen de app. Alleen bij wissen van de zoektekst verdwijnt hij (of een nieuw gekozen adres vervangt hem). De geselecteerde bestemming en lijststatus blijven alleen in de bestaande Android-`MapViewModel` in geheugen, niet op de server of in de zoekhistorie.
8. **Terug naar zoekadres** in het bestaande paneel onder de zoekbalk: de knop staat ook in de **ingeklapte** stand en verschijnt op basis van de **werkelijke kaartcamera** zodra de kaart voldoende van de gekozen bestemming is verschoven, onafhankelijk van de oorzaak (handmatig slepen, op een laadlocatie uit de lijst tikken of clusters navigeren). Een betekenisvolle handmatige zoomafwijking geeft dezelfde terugmogelijkheid. Een kleine zoomafhankelijke verplaatsingstolerantie voorkomt flikkeren bij pixel-jitter. Tik op de knop herstelt het **daadwerkelijk berekende kaartoverzicht** (de bewaarde centercoördinaten en dynamische zoom), niet langer een vaste zoom 14. Het paneel blijft ingeklapt, er opent geen detailsheet en er start geen nieuwe PDOK-/nearest-query; na terugcentreren verdwijnt de knop weer. Bij ontbreken van nearest-data blijft zoom 14 de fallback. Wissen van de zoekopdracht verwijdert pin, paneel en terugactie. Bij actieve location sheet wordt het paneel tijdelijk verborgen.
9. Filters (fase B) komen in een aparte wijziging; marker- en connector-deduplicatie blijven ongewijzigd.

## Performance en privacy

- Suggestiezoekopdrachten zijn asynchroon en **cancellable tot op OkHttp-call**; vertraagde vorige zoekantwoorden mogen niet nieuwe resultaten overschrijven.
- Suggesties zijn alleen lokaal in RAM gecachet (max. 40 keys, TTL 10 minuten); cachekey houdt rekening met tekst, ruwe biasregio van 0,1 graad en limiet. Geen persistente zoekhistorie, account of persoonsprofiel.
- Suggest en lookup: TLS zonder API-key; connect timeout 3 s en call timeout 6 s. De gebruiker krijgt een neutrale foutmelding bij storing, geen verzonnen resultaten.
- Vanuit de app gaat de getypte zoektekst (en kaartcentrum-bias) **rechtstreeks naar PDOK**. Dat is een externe gegevensverstrekking en wordt in de privacyverklaring vermeld vóór publieke release.
- De ChargeWatch-nearest-call blijft naar de bestaande server gaan. De directe PDOK-geocoder is niet afhankelijk van een NAS-release. Onderzoeken of PDOK live latencies voldoende zijn op de echte Samsung blijft een acceptatiestap.

## Acceptatietest

- Probeer `Oss`, `Stationsstraat Oss`, `5341 AB`, `Schietspoel 18 Oss`, een straat zonder huisnummer en een typefout.
- Type snel, verwijder letters, wissel naar een andere bestemming tijdens loading: geen oude zoekresultaten.
- Test `suggest → lookup → pin → nabij-lijst uitgeklapt → lijst inklappen/uitklappen (tap en swipe) → nearest aantikken (alleen kaart) → laadmarker aantikken (sheet)` en terug/wissen. Controleer dat de sheet en de resultatenlijst niet overlappen.
- Test de automatische kaartuitsnede nadat de nearest-query klaar is: bij meerdere locaties dicht op elkaar verder inzoomen, bij grotere spreiding uitzoomen; check dat blauwe pin plus vijf laadmarkers (mogelijk geclusterd) zichtbaar zijn **voor zover de 11,0–16,5 zoomlimiet dat toestaat**, en dat de uitgeklapte lijst geen markers bedekt. Test 0 of 1 nearest-resultaat en een zeer verre vijfde locatie. Geen ongewenste re-fit bij dichtklappen, handmatig pan/zoom of tabwissels.
- Test de **Terug naar zoekadres**-knop na **handmatig kaart slepen**, in-/uitzoomen, een laadlocatie via de lijst kiezen, en cluster-zoom. Controleer dat de knop ook met ingeklapte lijst beschikbaar is, het **oorspronkelijke dynamische kaartoverzicht** herstelt (niet zoom 14), daarna verdwijnt, en geen nieuwe nearest/PDOK-call maakt.
- Test de lijst met **5/1/0 dichtstbijzijnde locaties**; controleer dat een eerder opgeslagen favoriet een **goudgele ster** toont, een niet-favoriet **geen ster**, en dat favorietstatus verandert wanneer je die via de detailsheet aanpast. De rij zelf mag nog steeds alleen naar de kaart navigeren.
- Test behoud van de blauwe pin tijdens kaartbeweging, opnieuw typen, nabije laadlocatie aantikken en wisselen tussen Kaart/Favorieten/Instellingen; wissen moet de pin verwijderen. Test light/dark, zoom/cluster, favorieten en onbekende/stale feed.
- Meet op Samsung wanneer de eerste bruikbare suggesties verschijnen (niet alleen netwerkduur). Controleer gedrag bij offline, timeouts en hoge latency.
- Geen NAS-deployment, merge of productiestatus claimen voordat expliciet akkoord is gegeven.
