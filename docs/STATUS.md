# ChargeWatch — actuele projectstatus

**Peildatum:** 6 november 2026; v1-optie A voor #103 afgerond; kwaliteitsronde #104 opgesplitst in drie goedgekeurde werkblokken (#134–#136).  
**Bron van waarheid voor de actuele werkvoorraad:** [GitHub Issues](https://github.com/VanHinthem/ChargeWatch/issues?q=is%3Aissue+is%3Aopen) en [GitHub Pull Requests](https://github.com/VanHinthem/ChargeWatch/pulls?q=is%3Apr+is%3Aopen).  
**Historische werklogboeken:** [STATUS t/m 5 november](archive/STATUS_THROUGH_2026-11-05.md) en [oude roadmap](archive/ROADMAP_THROUGH_2026-11-05.md). De oude dagmeldingen en tussenstanden zijn **niet** de huidige openstaande taken.

## Huidige release- en deploymentgrens

- **Laatste gemergde Android-statistieken:** [PR #131](https://github.com/VanHinthem/ChargeWatch/pull/131), squash commit `92efcd12c322d539f00f8d5faac4548e2fe7ddf1`, fysiek op Samsung geaccepteerd.
- **Laatste gemergde API-functionaliteit:** [PR #130](https://github.com/VanHinthem/ChargeWatch/pull/130), squash commit `b6f2fef3395d7976b08de404480e63dbdb0e22b5`: historische betrouwbaarheidsverdelingen per EVSE/locatie via bestaande statistiekcall. Gebruiker bevestigde NAS-uitrol en een succesvolle publieke test. De laatste Samsung-geaccepteerde landelijke kaartwijziging is [PR #127](https://github.com/VanHinthem/ChargeWatch/pull/127) (`c7d7597540ac50c23f8749aaf16cd5b728e203b1`).
- **NAS/API:** gebruiker bevestigde op 5 november een deployment met de nieuwe batch- en gzip-functionaliteit. De publieke HTTPS-route retourneerde daadwerkelijk `HTTP/2 200`, `Content-Encoding: gzip` en `Vary: Accept-Encoding`. Dit bewijst de publieke gzip-response, niet automatisch dat de landelijke overview-featureflag aanstaat of dat de NAS elke latere `main`-commit draait.
- **Android:** GPS, PDOK-zoeken, kaartfilters, locatiesheets, favorieten en widgets zijn praktisch getest op Samsung. Ook de gecombineerde historische grafiek en EVSE-betrouwbaarheid uit PR #131 zijn door de gebruiker geaccepteerd, inclusief beide thema's, uurselectie en compacte lijst. Dit is **geen** formele v1-release-/Play Store-goedkeuring.
- **Landelijke kaart:** `GET /api/v1/map/overview` en `/api/v1/map/overview/cell` draaien op de actuele NAS-release met `CW_ENABLE_MAP_OVERVIEW=true` (door beheerder bevestigd, 6 november). Praktijk-HTTP-metingen en Samsung-acceptatie zijn geslaagd. De landelijke kaart is de standaardmodus; de tijdelijke kaartmodusschakelaar is verwijderd. De oude individuele viewport-/MapLibre-renderroute blijft in gebruik op straatniveau, met complete overview als fallback bij HTTP 413; er is geen onvolledige nationale NDW-fallback.

### #113 — NAS-validatie 6 november 2026

Read-only meting op de werkelijke NAS API, zonder nieuwe deployment of configuratiewijzigingen:
- Landelijk ongefilterd zoom 6: 82.785 locaties, 13 markers, 5 verzoeken p50 **1.431 ms**, hoogste **1.492 ms**.
- Landelijk CCS2/DC zoom 12: 1.614 locaties, 84 markers, p50 **161 ms**, hoogste **181 ms**.
- Oss zoom 15: 489 locaties, 116 markers, p50 **9 ms**, hoogste **14 ms**.
- Twee gelijktijdige landelijke requests: HTTP 200 in **2.045 ms** en **2.052 ms**, beide 82.785 locaties.
- Eén daadwerkelijke `/cell`-pagina: HTTP 200, **13 ms**, 15 items, `hasMore=false`.
- Alle genoemde overzichtsrequests: HTTP 200, `coverage.complete=true`, markerbudget ≤500.
- **Meetgrens:** vijf requests per scenario; de hoogste waarde is **geen statistisch betrouwbare p95**. Er is één cellpage geverifieerd, geen volledige multipage-uitputting.
- Fysieke Samsung-acceptatie van landelijke kaart, clustering, markers, filters, details, GPS/PDOK en offline/herstel door gebruiker bevestigd: werkt naar wens.
- Productbesluit geïmplementeerd in [PR #127](https://github.com/VanHinthem/ChargeWatch/pull/127): landelijke kaart standaard, tijdelijke instelling verwijderd; Android-/API-CI groen, bijgewerkte APK afzonderlijk fysiek op Samsung getest (door gebruiker bevestigd). Geen nieuwe NAS-deploy nodig. #113 afgesloten op expliciet gebruikersakkoord.

## Afgerond en gemerged

| Onderdeel | Resultaat | Bewijs |
|---|---|---|
| Bestemming zoeken + filters | Android zoeken, PDOK en directe filtertoepassing | [Issue #95](https://github.com/VanHinthem/ChargeWatch/issues/95) gesloten |
| GPS + vijf nabije laadlocaties en alternatieven | Uitgevoerd, settings/permissies en Samsung-acceptatie | [Issue #99](https://github.com/VanHinthem/ChargeWatch/issues/99) gesloten, [PR #109](https://github.com/VanHinthem/ChargeWatch/pull/109) gemerged |
| Landelijke overviewcontracten en natuurlijke/consistente clustering | Fase C + D, locale/regionale clustering en favorietsterren geïntegreerd | [PR #117–#122](https://github.com/VanHinthem/ChargeWatch/pulls?q=is%3Apr+is%3Amerged+113) en [#127](https://github.com/VanHinthem/ChargeWatch/pull/127) gemerged; #113 afgerond |
| Gerichte batched details | `POST /api/v1/locations/by-ndw-id/batch`, 1–20 ID's, optionele historie | [PR #124](https://github.com/VanHinthem/ChargeWatch/pull/124) gemerged |
| Gzip over JSON | `CompressionLevel.Fastest` met negotiated `Accept-Encoding: gzip`, ook voor kaarten en clusters | [PR #125](https://github.com/VanHinthem/ChargeWatch/pull/125) gemerged, publieke HTTPS-headercheck geslaagd |
| Android-cache en lazy statistieken | Details/tarieven gedeeld (2-minuten-TTL), historische statistieken op aanvraag (15-minuten-TTL) | [PR #123](https://github.com/VanHinthem/ChargeWatch/pull/123) gemerged en op Samsung geaccepteerd |
| Native Android-widgets | Responsive layouts (kleine tegel, tussenmaat, 4×1 en grotere formaten), directe favoriet, batch/cache, offline laatst-bekend | [PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112) gemerged, [issue #101](https://github.com/VanHinthem/ChargeWatch/issues/101) gesloten |

## #103 — afgeronde v1-statistieken (productbesluit optie A, 6 november)

Historische EVSE-betrouwbaarheid wordt afgeleid uit bestaande negen-categorie-EWMA per Nederlands weekdag-/uurvak. De read-only statistiekrespons bevat per actieve EVSE en op locatieniveau een additieve `reliability`-samenvatting (operationeel, storing, onbekend, gedekte/mogelijke uurvakken). Bestaande `slots`-velden en experimentele betekenis blijven ongewijzigd. Geen DB-migratie of importerwijziging voor dit blok; PR #130 is gemerged en de NAS-uitrol is door de gebruiker bevestigd.

Rekenregels, nuldata/UNKNOWN/PLANNED-gevallen en validatiegrenzen: [ISSUE_103_RELIABILITY_API_CONTRACT.md](ISSUE_103_RELIABILITY_API_CONTRACT.md). Het gecombineerde beschikbaarheidsgrafiek- en uitklapbare EVSE-ontwerp is opgenomen in [ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md](ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md) en geïmplementeerd in Android-PR #131. Ontwerp-PR #129 is daardoor redundant.

**V1-besluit optie A:** de huidige 168 weekdag-/uur-EWMA-profielen, groene historische beschikbaarheidsindicatie, oranje gemiddelde vrije EVSE's en betrouwbaarheid per EVSE/locatie vormen de geaccepteerde scope. De halfwaardetijd van 90 dagen is **geen** meting over een rollende periode van 90 dagen. De statistiek blijft `UNVALIDATED_SNAPSHOT_EWMA` / `isExperimental=true` en mag niet als gekalibreerde kans, gegarandeerde uptime of daadwerkelijke 90-daagse uptime worden gepresenteerd. Geen extra uptime-opslag, nieuwe rekenmethode of NAS-wijziging voor v1. De mogelijke bias door initialisatie op de eerste waarneming, kwaliteits-/publicatiecriteria en optionele echte rolling-90-day-uptime staan in **[vervolgissue #132](https://github.com/VanHinthem/ChargeWatch/issues/132)** (na v1, niet blokkerend). Releasekwaliteit wordt apart beoordeeld onder #104.

## #103 — historische statistieken op Android

- **API:** [PR #130](https://github.com/VanHinthem/ChargeWatch/pull/130) is op 6 november 2026 gesquasht naar `main` (`b6f2fef3395d7976b08de404480e63dbdb0e22b5`). De gebruiker bevestigde de nieuwe NAS/API-deployment en een publieke request naar locatie 67531 retourneerde de nieuwe `reliability`-objecten met 168/168 gedekte uurvakken voor EVSE 442–444.
- **Android-werkblok:** De bestaande read-only statistiekcall en de geschiedenis-cache (15 minuten) dragen één compleet `HistoricalStatistics`-object met uurkansen, historische vrije aantallen en locatie-/EVSE-betrouwbaarheid. De uitgeklapte sheet bevat een gecombineerde grafiek (groene historische kansbalken + oranje gemiddelde vrije-EVSE-lijn) en een standaard ingeklapt betrouwbaarheidsoverzicht per EVSE. Status UNKNOWN blijft apart, en ontbrekende cijfers worden niet vervangen door nul.
- **Functioneel ontwerp:** [ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md](ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md) is in deze Android-PR opgenomen. De eerdere documentatie-PR #129 is overbodig na merge van #131.
- **Verificatie:** Android-unit-tests, debug-APK-build, API-build, read-only databasecontract en map-overview HTTP-integratie groen voor PR #131. De gebruiker heeft de Android-weergave op Samsung goedgekeurd, inclusief selectie en light/dark mode. PR #131 is op 6 november gesquasht naar `main`. Productbesluit optie A: #103 v1-scope afgerond; ontbrekende 90-daagse uptime en kwaliteitsonderzoek expliciet uitgesteld naar #132. Geen nieuwe NAS-deployment of DB-migratie voor Android.

## Nog open — uitvoeringswerk

**[#104 — Android v1 kwaliteitsronde en releasevoorbereiding](https://github.com/VanHinthem/ChargeWatch/issues/104)** is de overkoepelende en enige resterende v1-releasegate. Op 6 november is de uitvoering in drie pragmatische werkblokken vastgesteld, in deze volgorde:

1. **[#134 — P4-A technische release-audit](https://github.com/VanHinthem/ChargeWatch/issues/134)** — controleer release-signing, versiebeleid, privacy/backup, NDW-/OpenFreeMap-attributie en licenties, publieke API-security en distributievoorwaarden. Eerst bestaande bewijzen en configuratie beoordelen; alleen daadwerkelijke blokkades oplossen. **Dit is de eerstvolgende actie.**
2. **[#135 — P4-B compacte Samsung-regressie](https://github.com/VanHinthem/ChargeWatch/issues/135)** — één gecombineerde functionele/UX-, offline/herstel- en stabiliteitsronde, inclusief widget-achtergrondverkeer en experimentele statistiekclaims. Niet nodeloos herhalen wat bij #113/#101/#103 is geaccepteerd.
3. **[#136 — P4-C releasecandidate en go/no-go](https://github.com/VanHinthem/ChargeWatch/issues/136)** — reproduceerbaar gesigneerde kandidaat, installatie/upgrade, checklist, vastgelegde beperkingen en expliciet afzonderlijk distributiebesluit.

**Beoordelingsgrens:** nieuwe issues zijn planning en geen bewijs van afgeronde test, opgeloste bug of releasegereedheid. Er zijn tijdens de planningsstap geen code-, API-, DB- of NAS-wijzigingen uitgevoerd. Waar verificatie geen wijzigingen vereist is geen aparte PR nodig; gerelateerde fixes bundelen. Merges, NAS-wijzigingen en publicatie uitsluitend met apart akkoord.

## Uitgesteld / onderzoek, niet blokkerend voor v1

- **[#132 — EWMA-opbouwfase, datakwaliteit en eventuele 90-daagse uptime](https://github.com/VanHinthem/ChargeWatch/issues/132):** expliciet buiten v1 geplaatst bij optie A. Onderzoek warm-up-bias, kwaliteits/publicatiedrempels en of echte tijdgebaseerde uptime later meerwaarde heeft.

- [#110 — Android Auto](https://github.com/VanHinthem/ChargeWatch/issues/110): onderzoek naar veilige favorieten/navigatie via Android Auto; geen implementatiebesluit.
- [#79 — datakwaliteits-/monitoringdashboard](https://github.com/VanHinthem/ChargeWatch/issues/79): historiek/trends en bronkwaliteit; uitgesteld. De bestaande operationele status in Android-instellingen is geen volledige uitvoering.
- **Eventuele 1×1-widget:** technisch mogelijke toekomstige uitbreiding; **niet** als apart issue ingepland of gerealiseerd. De bestaande kleine Samsung-widget is niet aantoonbaar een zelfstandige 1×1 launcherwidget.

## PR-/issueadministratie

- [Issue #101](https://github.com/VanHinthem/ChargeWatch/issues/101): **completed** na merge PR #112 en Samsung-acceptatie.
- [Issue #113](https://github.com/VanHinthem/ChargeWatch/issues/113): **completed** na NAS-HTTP-validatie, Samsung-acceptatie, merge PR #127 en test van de bijgewerkte APK.
- [PR #54](https://github.com/VanHinthem/ChargeWatch/pull/54): **gesloten zonder merge** op verzoek gebruiker; oude mogelijke NDW-dekkingsnotitie Geffen niet meer nodig.
- [PR #131](https://github.com/VanHinthem/ChargeWatch/pull/131): **gemerged** (`92efcd12...`), gecombineerde historie en EVSE-betrouwbaarheid door gebruiker geaccepteerd. [PR #129](https://github.com/VanHinthem/ChargeWatch/pull/129) zonder merge gesloten; het ontwerp staat in `main` via #131.
- [Issue #103](https://github.com/VanHinthem/ChargeWatch/issues/103): v1-scope **afgerond op optie A**; niet-gerealiseerde echte 90-daagse uptime en kalibratieonderzoek naar [#132](https://github.com/VanHinthem/ChargeWatch/issues/132) verplaatst.
- Openstaande v1-uitvoering: **#104 met werkblokken #134, #135 en #136**. Uitgestelde/onderzoeksissues: **#132, #110 en #79**.

## Werkwijze

Houd technische wijzigingen in kleine, reviewbare PR's met CI. NAS-updates, productiefeatureflags, Android-release en merges worden afzonderlijk geautoriseerd. Bewaar oude ontwerpbesluiten en benchmarkgegevens als historie, maar gebruik dit actuele overzicht in plaats van oude opmerkingen als *draft*, *niet gemerged* of *nog geen implementatie*.

**Volgende actie:** #134 technische release-audit onder #104; daarna #135 Samsung-kwaliteitsronde en #136 releasecandidate/go-no-go. #132 is uitgesteld en geen v1-gate.
