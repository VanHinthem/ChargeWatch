# ChargeWatch — actuele projectstatus

**Peildatum:** 6 november 2026, na de #113 NAS-HTTP- en Samsung-acceptatie.  
**Bron van waarheid voor de actuele werkvoorraad:** [GitHub Issues](https://github.com/VanHinthem/ChargeWatch/issues?q=is%3Aissue+is%3Aopen) en [GitHub Pull Requests](https://github.com/VanHinthem/ChargeWatch/pulls?q=is%3Apr+is%3Aopen).  
**Historische werklogboeken:** [STATUS t/m 5 november](archive/STATUS_THROUGH_2026-11-05.md) en [oude roadmap](archive/ROADMAP_THROUGH_2026-11-05.md). De oude dagmeldingen en tussenstanden zijn **niet** de huidige openstaande taken.

## Huidige release- en deploymentgrens

- **Laatste functionele merge:** [PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112), squash commit `92d7e441e70a86e11bb5b99094e325c3b009f7e6`. De widget is in dezelfde Android-app opgenomen, met geslaagde Android-/API-CI en fysieke Samsung-acceptatie.
- **NAS/API:** gebruiker bevestigde op 5 november een deployment met de nieuwe batch- en gzip-functionaliteit. De publieke HTTPS-route retourneerde daadwerkelijk `HTTP/2 200`, `Content-Encoding: gzip` en `Vary: Accept-Encoding`. Dit bewijst de publieke gzip-response, niet automatisch dat de landelijke overview-featureflag aanstaat of dat de NAS elke latere `main`-commit draait.
- **Android:** GPS, PDOK-zoeken, kaartfilters, locatiesheets, favorieten en de widgets zijn geïmplementeerd en praktisch getest op Samsung. De meest recente APK-tests zijn **geen** formele v1-release-/Play Store-goedkeuring.
- **Landelijke kaart:** `GET /api/v1/map/overview` en `/api/v1/map/overview/cell` draaien op de actuele NAS-release met `CW_ENABLE_MAP_OVERVIEW=true` (door beheerder bevestigd, 6 november). Praktijk-HTTP-metingen en Samsung-acceptatie zijn geslaagd. De landkaart wordt de standaardmodus; de tijdelijke kaartmodusschakelaar vervalt. De oude individuele viewport-/MapLibre-renderroute blijft in gebruik op straatniveau, met complete overview als fallback bij HTTP 413; er is geen onvolledige nationale NDW-fallback.

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
- Productbesluit: landelijke kaart voortaan standaard; tijdelijke keuze verwijderen. De codewijziging is onderdeel van de gerichte #113-afrondings-PR, geen nieuwe NAS-deploy noodzakelijk. Issue blijft open tot review, CI, merge en afsluitakkoord.

## Afgerond en gemerged

| Onderdeel | Resultaat | Bewijs |
|---|---|---|
| Bestemming zoeken + filters | Android zoeken, PDOK en directe filtertoepassing | [Issue #95](https://github.com/VanHinthem/ChargeWatch/issues/95) gesloten |
| GPS + vijf nabije laadlocaties en alternatieven | Uitgevoerd, settings/permissies en Samsung-acceptatie | [Issue #99](https://github.com/VanHinthem/ChargeWatch/issues/99) gesloten, [PR #109](https://github.com/VanHinthem/ChargeWatch/pull/109) gemerged |
| Landelijke overviewcontracten en natuurlijke/consistente clustering | Fase C + D, locale/regionale clustering en favorietsterren geïntegreerd | [PR #117–#122](https://github.com/VanHinthem/ChargeWatch/pulls?q=is%3Apr+is%3Amerged+113) gemerged; #113 blijft open voor rollout |
| Gerichte batched details | `POST /api/v1/locations/by-ndw-id/batch`, 1–20 ID's, optionele historie | [PR #124](https://github.com/VanHinthem/ChargeWatch/pull/124) gemerged |
| Gzip over JSON | `CompressionLevel.Fastest` met negotiated `Accept-Encoding: gzip`, ook voor kaarten en clusters | [PR #125](https://github.com/VanHinthem/ChargeWatch/pull/125) gemerged, publieke HTTPS-headercheck geslaagd |
| Android-cache en lazy statistieken | Details/tarieven gedeeld (2-minuten-TTL), historische statistieken op aanvraag (15-minuten-TTL) | [PR #123](https://github.com/VanHinthem/ChargeWatch/pull/123) gemerged en op Samsung geaccepteerd |
| Native Android-widgets | Responsive layouts (kleine tegel, tussenmaat, 4×1 en grotere formaten), directe favoriet, batch/cache, offline laatst-bekend | [PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112) gemerged, [issue #101](https://github.com/VanHinthem/ChargeWatch/issues/101) gesloten |

## Nog open — uitvoeringswerk

1. **[#113 — landelijke kaart standaard en administratieve afronding](https://github.com/VanHinthem/ChargeWatch/issues/113).** NAS HTTP-probes en Samsung-acceptatie geslaagd. Productbesluit: landelijke overview als standaard, tijdelijke kaartmoduskeuze weg. Gerichte Android-wijziging, CI/review en expliciet merge-/issue-afsluitakkoord resteren; geen herhaling van bestaande SQL-pariteitsprobes.
2. **[#103 — historische statistieken en 90-daagse uptime](https://github.com/VanHinthem/ChargeWatch/issues/103).** Publicatiegrenzen onderbouwen voor echte observatiedekking, null/experimentele data, betrouwbaarheid en bronversheid; vervolgens EVSE-uptime/locatiehistoriek correct in de app presenteren. Geen voorspelling of betrouwbaar percentage zonder bewijs.
3. **[#104 — Android v1 kwaliteitsronde en releasevoorbereiding](https://github.com/VanHinthem/ChargeWatch/issues/104).** Volledige integrale Samsung-regressie, accessibility, offline/fouten, batterij/netwerk, signing, versiebeleid, privacy/attributie, API-beveiliging en expliciete releasebeslissing. Eerst relevante kwaliteitspunten uit #103 en #113 afronden of expliciet uitstellen.

## Uitgesteld / onderzoek, niet blokkerend voor v1

- [#110 — Android Auto](https://github.com/VanHinthem/ChargeWatch/issues/110): onderzoek naar veilige favorieten/navigatie via Android Auto; geen implementatiebesluit.
- [#79 — datakwaliteits-/monitoringdashboard](https://github.com/VanHinthem/ChargeWatch/issues/79): historiek/trends en bronkwaliteit; uitgesteld. De bestaande operationele status in Android-instellingen is geen volledige uitvoering.
- **Eventuele 1×1-widget:** technisch mogelijke toekomstige uitbreiding; **niet** als apart issue ingepland of gerealiseerd. De bestaande kleine Samsung-widget is niet aantoonbaar een zelfstandige 1×1 launcherwidget.

## PR-/issueadministratie

- [Issue #101](https://github.com/VanHinthem/ChargeWatch/issues/101): **completed** na merge PR #112 en Samsung-acceptatie.
- [PR #54](https://github.com/VanHinthem/ChargeWatch/pull/54): **gesloten zonder merge** op verzoek gebruiker; oude mogelijke NDW-dekkingsnotitie Geffen niet meer nodig.
- Overige open code-PR's op peildatum: **geen**. Documentatie-opschoning gebeurt via een afzonderlijke PR.
- Actieve open issues na afsluiten #101: **#113, #103, #104, #110, #79**. De twee laatste zijn uitgesteld; alleen #113/#103/#104 vragen nog v1-gerelateerd werk.

## Werkwijze

Houd technische wijzigingen in kleine, reviewbare PR's met CI. NAS-updates, productiefeatureflags, Android-release en merges worden afzonderlijk geautoriseerd. Bewaar oude ontwerpbesluiten en benchmarkgegevens als historie, maar gebruik dit actuele overzicht in plaats van oude opmerkingen als *draft*, *niet gemerged* of *nog geen implementatie*.

**Volgende actie:** de gerichte #113-Android-defaultwijziging reviewen en valideren; na afzonderlijk merge-/afsluitakkoord verder met #103 statistiekbetrouwbaarheid en daarna #104 releasekwaliteit.
