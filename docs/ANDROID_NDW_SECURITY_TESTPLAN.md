# Android NDW-first architectuur, API-beveiliging en testplan
Datum: 24 oktober 2026. Status: **voorkeursarchitectuur / beslisdocument**; geen appcode of publieke API-ontsluiting in deze PR.

## 1. Product- en datastromen
- **Normaal kaartgebruik:** Android haalt NDW GeoJSON rechtstreeks op per zichtbaar kaartgebied; de ChargeWatch API wordt niet gebruikt om iedere marker te verrijken.
- **Locatiedetails:** pas bij openen van details vraagt Android gerichte historische/diagnostische verrijking op bij ChargeWatch. Voor details ontbreken nog een stabiel extern-ID-lookupcontract en een expliciete reactie bij ontbrekende historische gegevens; de bestaande `/api/v1/locations/{id}/statistics` gebruikt een interne numerieke DB-ID.
- **NDW-fallback:** bij bereikbaarheid-/server-/limietfouten of onbruikbare NDW-respons vraagt Android kaart-/locatiedata op bij ChargeWatch, met bron- en feedversheidsindicaties. HTTP 429 leidt eerst tot `Retry-After`/backoff en waar nodig fallback; geen herhaald stormverkeer naar beide providers.
- **Favorieten, aliassen, volgorde en voorkeuren blijven alleen lokaal op Android.** Geen gebruikersaccounts of centrale favorietendatabase.
- **Widget:** zelfde NDW-eerst/fallbackbeleid, met Android-periodic-work-beperkingen. Of NDW één specifieke favoriet efficiënt kan ophalen moet nog worden gevalideerd. Geen honderden kleine bbox-verzoeken per widget-refresh.
- **Collector/DB:** landelijke NDW-import en compacte statische profielen blijven doorlopen als fallback en voor toekomstige statistiek. Statistische kansen blijven verborgen (`null`) zolang `docs/SPRINT3_PUBLICATION_POLICY.md` niet is voldaan. Geen ruwe histories toevoegen.

## 2. NDW externe contracten / nog te verifiëren
Documentatie: https://docs.ndw.nu/data-uitwisseling/interface-beschrijvingen/dafne-api/dafne_api_consumer_pull/
- Gedocumenteerde GeoJSON-route: `GET https://dotnl.ndw.nu/api/rest/geojson/dynamic-road-status/charge-point-data/v1/features?bbox=minLon,minLat,maxLon,maxLat`.
- Gedocumenteerde bbox-limieten: maximaal **1,0 vierkante graad**, **1.000 features**, **10 requests/s**, bij overschrijding **429**. De API is volgens NDW nog in ontwikkeling.
- **Niet vastgesteld**: of 10 requests/s per publiek IP, per gebruiker, per app of globaal geldt; rate-limit headers, auth-/hergebruikbeleid voor publieke mobiele distributie, detailinhoud, contractversies, individuele ID-lookup, coverage, ETag/cachecontrole en CORS (CORS niet vereist voor native Android).
- **Niet aannemen** dat iedere gebruiker gegarandeerd eigen quotum heeft: NAT/CGNAT delen IP-adressen.
- Kaart: debounce bewegingen, cancel oude requests, zoom-/bbox-grootte begrenzen, kleine cache met vervaltijd, omgaan met 429/5xx/timeout, begrensde retries met jitter en attributie/licentie controleren.
- **Identity gate:** NDW GeoJSON moet aantoonbaar stabiel koppelen aan `countryCode + partyId + sourceId` in onze Postgres-database. Geen koppeling enkel op coördinaten of toevallig overeenkomende straatnaam. Test ontbrekende/duplicaat-/gewijzigde bron-ID en verschillend volgorde-/formaat.
- **Live NDW-verificatie vereist vóór Android-implementatie:** respons rond Oss vergelijken met `/api/v1/locations` en `/api/v1/locations/{id}`, velddekking/status/timestamps, 1000-limiet/paginering en beschikbaarheid van tariefdetails zonder landelijke bulkdownload.

## 3. Beveiliging publieke ChargeWatch API
Dit is het beoogde model, **nog niet geïmplementeerd als productiehandhaving**.
- Alleen HTTPS via gecontroleerde reverse proxy. PostgreSQL-rol `chargewatch_read` heeft SELECT-only; collector blijft uitsluitend schrijvend. Geen NDW-, DB-, Google-service-credentials, vaste clientsecret of bearer-token in APK.
- **Play Integrity (Standard requests)** voor ChargeWatch-verrijkings- en fallbackverzoeken. Android vraagt een integriteitsbewijs bij relevante actie; server decodeert/verifieert via Google met eigen service-accountcredentials. Verifieer app-pakketnaam, Play-herkenning, certificaat/signing-signaal, tijdigheid en `requestHash` die aan methode, canonieke route en relevante queryparameters/body gebonden is. Exacte checkvelden en foutbeleid volgen de officiële documentatie; implementeer geen zelfbedachte token-validatie.
- Google-ondersteunde replaymitigatie voor Standard requests én passende applicatiebeveiliging; Play Integrity is een **anti-misbruiksignaal**, geen waterdichte authentisatie. Geen vervanging voor serverzijde-limieten. Beoordeel toegankelijkheid van niet-Play-installaties en ontwikkel-/testbouwvarianten expliciet voordat handhaving wordt ingeschakeld.
- Ongeautoriseerde of ongeldige integriteitsbewijzen: vastgelegd fail-closed/fail-open **besluit vóór publieke uitrol**, zodat er geen onbedoelde open bypass ontstaat bij Google-uitval. Geen onbeveiligde debugoptie in productie; testmodus uitsluitend intern.
- Huidig basis-IP-rate-limitbeleid (120/min, zie implementatie) behouden tijdens interne fase. Ontwerp ter beoordeling: aparte limiter voor kaartfallback en voor details/statistieken, correcte 429 + `Retry-After`, volledige app-verantwoordelijkheid voor retry/backoff; exacte quota afstellen door loadtests in plaats van willekeurig vastzetten.
- **Correct IP achter reverse proxy:** alleen expliciet vertrouwde proxies/forwarded headers; onbetrouwbare `X-Forwarded-For` nooit klakkeloos vertrouwen. NAT/CGNAT behandelen zonder legitieme gebruikers onnodig af te snijden.
- Bounded page size/response, query/bbox-validatie, database-command-timeouts, maximaal gelijktijdige queries en beperkte logging zonder tokens/geolocatie als onnodige PII. Ook bij een geldig integriteitsbewijs gelden alle quotaregels.
- Publieke API mag niet worden opengesteld voordat de beveiligingsgate groen is. NAS is nu uitsluitend intern via `127.0.0.1:5088`.

## 4. Testplan en acceptatiecriteria (klein en doelgericht)
### A. NDW onderzoeks-/contracttests (eerst)
1. Native of CLI GET met kleine bbox rondom Oss: HTTP-code, GeoJSON-structuur, aantal features, EVSE-status en timestampvelden vergelijken met Postgres/ChargeWatch.
2. OCPI-matching bij minimaal enkele echte locatierecords plus negatieve gevallen (ontbrekende IDs, duplicaten, verhuisde locatie).
3. Fallbacksimulatie: NDW timeout, 429 (respecteer `Retry-After`), 500, lege/onverwachte JSON, incomplete resultaten/1000-features. Geen oneindige retries of blokkeren van kaart.
4. Belastingsproef met gesimuleerde kaart-pan/zoom om caching, debounce en netwerkverbruik aan te tonen. Niet werkelijk de NDW-productie-API belasten om limieten te forceren; mock voor 429-storm.
5. Vaststellen of rate limiting per IP of globaal geldt en publieke app-licentie/auth mogelijk is; schriftelijk navragen bij NDW indien onduidelijk.

### B. Android-clienttests
1. Kaart: alleen NDW-verzoeken bij normale route; **0** ChargeWatch-verrijkingsrequests bij pan/zoom.
2. Detail openen: **1** gerichte verrijking, correct gematchte OCPI-ID; bij geen statistiek blijft detail werken zonder percentages.
3. NDW-fallback: juiste ChargeWatch-query, duidelijke dataversheidslabels; herstel naar NDW zonder onnodige extra belasting.
4. Favorieten/aliases/volgorde blijven na apprestart lokaal, **geen user write naar backend**; widget gebruikt dezelfde retrieval-regels en beperkt achtergrondverkeer.
5. Test netwerkcache, offline modus, 429/backoff, HTTP-fouten, nonmatching API-schema en onderbroken verbinding.

### C. ChargeWatch-server/securitytests vóór publieke uitrol
1. Play Integrity: geldige, ongeldige, verlopen, gemanipuleerde, verkeerde package/signing identity en verkeerde `requestHash`; replay-/token-hergebruikgedrag en Google-foutscenario.
2. Verifieer dat API-secret/service-account alleen server-side is; HTTP zonder vereist integriteitsbewijs krijgt passend denial-response wanneer handhaving aan staat. Dataleak van token naar logs uitsluiten.
3. Rate-limit: grens, `429`/`Retry-After`, proxy-IP spoofing, veel gelijktijdige clients achter één IP en globale NAS/DB-belasting.
4. Contract: batch **niet** nodig voor kaart; wel gerichte OCPI-ID-statistieklookup voor details. Tests op onbekende/gewijzigde IDs, geen data, en `availableProbability=null` bij onvoldoende data.
5. CI: .NET API build+selftests/read-only rolecontract; Android build/unit tests zodra project bestaat; gesimuleerde NDW responses en integriteitsverdicts, **geen echte Google/NDW-aanroepen in iedere CI-run**.

### D. Releasegates
- NDW-feedspecificatie en identiteitsmapping live gevalideerd.
- API-verrijkingscontract inclusief publieke ID vastgelegd en getest.
- Reverse-proxy HTTPS, Play Integrity handhaving, quotas, proxy-IP vertrouwen, timeout/capaciteit en logging gevalideerd.
- Fallback met verouderde statussen aantoonbaar begrijpelijk; tarief- en bronvermeldingsvoorwaarden gecontroleerd.
- Productie-credentials server-side; geen accounts/favorieten op server; expliciete gebruikerstoestemming voor publieke deployment.

## 5. Werkvolgorde
1. Onderzoek NDW GeoJSON en OCPI-ID-matching met testdata/een klein Oss-gebied.
2. Android basis (Kotlin/Compose), kaartenclient NDW-first, lokale favorieten, fallback en locatiedetail.
3. Gerichte read-only verrijkingsendpoint op stabiele externe sleutel, met publicatiegate voor statistieken.
4. Play Integrity serververificatie, reverse-proxy & NAS-hardening, quota en testen vóór publieke distributie.
5. Widget en releasecheck.

Dit document legt besluiten en **nog open verificatiepunten** vast; het is geen claim dat NDW-limiet per IP geldt of dat Play Integrity reeds actief is.
