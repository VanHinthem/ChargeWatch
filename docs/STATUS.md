# ChargeWatch — actuele projectstatus

**Peildatum:** 6 november 2026, na NAS-uitrol van API-PR #130 en Samsung-acceptatie van Android-PR #131 (squash-merge na expliciet akkoord).  
**Bron van waarheid voor de actuele werkvoorraad:** [GitHub Issues](https://github.com/VanHinthem/ChargeWatch/issues?q=is%3Aissue+is%3Aopen) en [GitHub Pull Requests](https://github.com/VanHinthem/ChargeWatch/pulls?q=is%3Apr+is%3Aopen).  
**Historische werklogboeken:** [STATUS t/m 5 november](archive/STATUS_THROUGH_2026-11-05.md) en [oude roadmap](archive/ROADMAP_THROUGH_2026-11-05.md). De oude dagmeldingen en tussenstanden zijn **niet** de huidige openstaande taken.

## Huidige release- en deploymentgrens

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

## #103 — betrouwbaarheids-API (afgerond implementatieblok)

Historische EVSE-betrouwbaarheid wordt afgeleid uit bestaande negen-categorie-EWMA per Nederlands weekdag-/uurvak. De read-only statistiekrespons bevat per actieve EVSE en op locatieniveau een additieve `reliability`-samenvatting (operationeel, storing, onbekend, gedekte/mogelijke uurvakken). Bestaande `slots`-velden en experimentele betekenis blijven ongewijzigd. Geen DB-migratie of importerwijziging voor dit blok; PR #130 is gemerged en de NAS-uitrol is door de gebruiker bevestigd.

Rekenregels, nuldata/UNKNOWN/PLANNED-gevallen en validatiegrenzen: [ISSUE_103_RELIABILITY_API_CONTRACT.md](ISSUE_103_RELIABILITY_API_CONTRACT.md). Het gecombineerde beschikbaarheidsgrafiek- en uitklapbare EVSE-ontwerp is opgenomen in [ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md](ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md) en geïmplementeerd in Android-PR #131. Ontwerp-PR #129 is daardoor redundant.

**Resterend binnen #103:** oorspronkelijke publicatiecriteria op basis van onafhankelijke observatiedekking, bronversheid, statuskwaliteit en de feitelijke 90-daagse EVSE-uptime zijn niet afgedekt door de EWMA-weergave. Geen claim van bewezen 90-daagse uptime of gekalibreerde kans. #104 releasekwaliteit blijft daarna open. Een publieke API-uitrol en Samsung-acceptatie vereisen afzonderlijk akkoord.

## #103 — historische statistieken op Android

- **API:** [PR #130](https://github.com/VanHinthem/ChargeWatch/pull/130) is op 6 november 2026 gesquasht naar `main` (`b6f2fef3395d7976b08de404480e63dbdb0e22b5`). De gebruiker bevestigde de nieuwe NAS/API-deployment en een publieke request naar locatie 67531 retourneerde de nieuwe `reliability`-objecten met 168/168 gedekte uurvakken voor EVSE 442–444.
- **Android-werkblok:** De bestaande read-only statistiekcall en de geschiedenis-cache (15 minuten) dragen één compleet `HistoricalStatistics`-object met uurkansen, historische vrije aantallen en locatie-/EVSE-betrouwbaarheid. De uitgeklapte sheet bevat een gecombineerde grafiek (groene historische kansbalken + oranje gemiddelde vrije-EVSE-lijn) en een standaard ingeklapt betrouwbaarheidsoverzicht per EVSE. Status UNKNOWN blijft apart, en ontbrekende cijfers worden niet vervangen door nul.
- **Functioneel ontwerp:** [ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md](ISSUE_103_HISTORICAL_STATISTICS_UX_DESIGN.md) is in deze Android-PR opgenomen. De eerdere documentatie-PR #129 is overbodig na merge van #131.
- **Verificatie:** Android-unit-tests, debug-APK-build, API-build, read-only databasecontract en map-overview HTTP-integratie groen voor PR #131. De gebruiker heeft de Android-screenshots en het gedrag op Samsung goedgekeurd, inclusief selectie en weergave in light/dark mode. **Mergeakkoord verleend op 6 november.** Geen nieuwe NAS-deployment of DB-migratie voor Android. #103 blijft open voor nog niet bewezen uptime-/publicatiecriteria; #104 releasekwaliteit daarna.

## Nog open — uitvoeringswerk

1. **[#103 — historische statistieken en 90-daagse uptime](https://github.com/VanHinthem/ChargeWatch/issues/103).** Publicatiegrenzen onderbouwen voor echte observatiedekking, null/experimentele data, betrouwbaarheid en bronversheid; vervolgens EVSE-uptime/locatiehistoriek correct in de app presenteren. Geen voorspelling of betrouwbaar percentage zonder bewijs.
2. **[#104 — Android v1 kwaliteitsronde en releasevoorbereiding](https://github.com/VanHinthem/ChargeWatch/issues/104).** Volledige integrale Samsung-regressie, accessibility, offline/fouten, batterij/netwerk, signing, versiebeleid, privacy/attributie, API-beveiliging en expliciete releasebeslissing. Eerst relevante kwaliteitspunten uit #103 en #113 afronden of expliciet uitstellen.

## Uitgesteld / onderzoek, niet blokkerend voor v1

- [#110 — Android Auto](https://github.com/VanHinthem/ChargeWatch/issues/110): onderzoek naar veilige favorieten/navigatie via Android Auto; geen implementatiebesluit.
- [#79 — datakwaliteits-/monitoringdashboard](https://github.com/VanHinthem/ChargeWatch/issues/79): historiek/trends en bronkwaliteit; uitgesteld. De bestaande operationele status in Android-instellingen is geen volledige uitvoering.
- **Eventuele 1×1-widget:** technisch mogelijke toekomstige uitbreiding; **niet** als apart issue ingepland of gerealiseerd. De bestaande kleine Samsung-widget is niet aantoonbaar een zelfstandige 1×1 launcherwidget.

## PR-/issueadministratie

- [Issue #101](https://github.com/VanHinthem/ChargeWatch/issues/101): **completed** na merge PR #112 en Samsung-acceptatie.
- [Issue #113](https://github.com/VanHinthem/ChargeWatch/issues/113): **completed** na NAS-HTTP-validatie, Samsung-acceptatie, merge PR #127 en test van de bijgewerkte APK.
- [PR #54](https://github.com/VanHinthem/ChargeWatch/pull/54): **gesloten zonder merge** op verzoek gebruiker; oude mogelijke NDW-dekkingsnotitie Geffen niet meer nodig.
- Android-PR #131 is na fysieke Samsung-acceptatie goedgekeurd voor squash-merge. Documentatie is aan dezelfde PR toegevoegd; geen afzonderlijke ontwerpmerge nodig.
- Actieve open issues na afsluiten #113: **#103, #104, #110, #79**. De twee laatste zijn uitgesteld; alleen #103 en #104 vragen nog v1-gerelateerd werk.

## Werkwijze

Houd technische wijzigingen in kleine, reviewbare PR's met CI. NAS-updates, productiefeatureflags, Android-release en merges worden afzonderlijk geautoriseerd. Bewaar oude ontwerpbesluiten en benchmarkgegevens als historie, maar gebruik dit actuele overzicht in plaats van oude opmerkingen als *draft*, *niet gemerged* of *nog geen implementatie*.

**Volgende actie:** resterende publicatiecriteria en werkelijke 90-daagse uptime binnen #103 afzonderlijk afbakenen of expliciet uitstellen; daarna #104 Android-v1 releasekwaliteit.
