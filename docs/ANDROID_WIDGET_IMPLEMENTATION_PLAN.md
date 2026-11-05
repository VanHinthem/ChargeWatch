# Android-startschermwidget — UX-contract en technisch ontwerp (#101)

**Status (2 november 2026):** minimalistische UX-richting A goedgekeurd en vervolgens expliciet akkoord gegeven voor Android-implementatie. Uitvoering op `feature/android-favorites-widget` in [draft-PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112). CI en Samsung-validatie nog te voltooien; **geen merge of deploy zonder afzonderlijk expliciet akkoord**. Canoniek issue: [#101](https://github.com/VanHinthem/ChargeWatch/issues/101). De oude documentatie-PR #108 (`docs/android-v1-next-steps`) heeft een conflicterende historische roadmap en is **niet** de implementatiebasis; de relevante punten zijn hier opnieuw tegen `main` beoordeeld.

## Doel en productgrenzen

- Eén **native Android-startschermwidget** binnen de bestaande APK en dezelfde app-module, met één responsive implementatie op basis van **Jetpack Glance**.
- In één oogopslag zien hoeveel EVSE's bij gekozen favoriete laadlocaties vrij zijn. Geen mini-kaart, tarief, connectorinformatie, afstand, historische minigrafiek, account, notificatie of eigen navigatie in deze eerste widgetversie.
- Bestaande **lokale** favorieten blijven de enige bron voor `featureId`, alias, adres/coördinaten en volgorde. Geen cloudopslag, write-endpoint, nieuwe locatiepermissie, GPS-tracking of NAS-/DB-wijziging.
- De gebruiker kiest **maximaal vijf favorieten per widgetinstantie**; als er minder dan twee favorieten bestaan, kan ook één worden getoond. Iedere instantie heeft eigen selectie en volgorde, los van de algemene favorietenlijst.
- Geen aparte APK of app: toevoegen/updaten van de bestaande APK installeert/bijwerkt ook widgetreceiver, configuratie en achtergrondtaak.

## Vervolg: geografische requestgrootte en incrementele widgetupdates (2 november 2026)

De directe `Verversen…`-indicatie werkt, maar de gebruiker ziet gewijzigde favorieten en aantallen nog vertraagd. Het bestaande `FavoritesAvailabilityClient`-algoritme combineerde laadlocaties uit uiteenliggende regio's soms tot een veel te brede viewport (`areaSquareDegrees <= 0.05`) en wachtte via `awaitAll()` tot alle (soms fallbackend) regio's klaar waren om **ook snel gereedliggende** locaties te tonen.

**Scopebeperkte Android-optimalisatie:** combinatie alleen voor werkelijk nabije punten (`dx,dy <= 0.015°` en `area <= 0.0002°²`); sonst onafhankelijke kleine viewports parallel. Dezelfde client biedt een optionele `onPartialLocations`-callback. Na afronding van één viewport worden alleen ontvangen favoriete `featureId`s in `WidgetStore.recordIncremental()` opgeslagen en Glance direct bijgewerkt. `refreshingSinceMillis` wordt pas gewist na de finale overall refresh; oude validerende status blijft bewaard bij onbetrouwbare/ontbrekende data. Standaard bestaande appclient zonder callback behoudt zijn eindresultaat-`Success/Partial`-contract. Geen nieuwe endpoint, polling of databetrouwbaarheidscompromis.

Tests valideren groeperen van nabije locaties en opsplitsen van een Oss/Elst-achtig geografisch paar. **Nog fysiek te toetsen:** snelheid eerste vs. laatste locatie en zichtbaarheid configuratie in verschillende widgetformaten.

---

## Directe widgetconfiguratie en refresh — vijfde Samsung-feedback

Gebruiker meldt dat **opslaan van favorieten en handmatige dataverversing te traag reageren**. Root cause in oude Android-code: de widget tekent een vooraf gelezen snapshot en kan actief blijven zonder recompositie op DataStore-wijzigingen; daarnaast werd handmatig refreshen in WorkManager gequeued met KEEP-policy en de 60s gedeelde throttle.

**Correctie:** lokale selectie opslaan en **direct** tekenen via `states.collectAsState()` en `favorites.collectAsState()` *binnen Glance `provideContent`*. Verversactie + het opslaan van de configuratie laten via een procesbrede coroutine meteen de bestaande `FavoritesAvailabilityClient` op IO draaien, gecoördineerd door één Mutex. Zet status `Verversen…` direct en vervang de ratio alleen bij betrouwbare teruggeleverde gegevens. Een aparte expedited WorkManager request (Android 12+, oudere versies gewone backup) biedt herstel als Android het proces beëindigt; periodiek 15 minuten blijft best effort. Expliciet gebruik heeft korte 2s dubbelklikbescherming, periodiek gebruik behoudt 60s throttle. Het inlezen van onafhankelijke adresmetadata blokkeert de EVSE-update niet. Maak op voorhand geen belofte van instant netwerkantwoord.

**Tests:** handmatig bypass van 60s, uitgestelde periodieke request, geen dubbele taps, cacheloos geselecteerd favoriet, loadingstatus na proceskill en regressie voor bestaande kleuren/adressen/layout. Samsung-test: widget herconfigureren (selectie, volgorde, alias), direct aantallen ophalen via verversknop, offline/vangnet en bronversheid. **Nog geen merge/deploy.**

---

## Vierde Samsung-acceptatieronde: compact 2×2 en 4×1-bronversheid

Bij de door gebruiker aangeleverde screenshots werkt de echte **4×1-resizetoestand op Samsung**. 4×2 toont alle twee favorieten correct; 2×2 is te krap om de lange titel, twee adressen en hoge ratio's tegelijk leesbaar te houden. De vervolgimplementatie op PR #112 laat de 4×2-renderer ongewijzigd, maar introduceert voor smalle widgets (`widthDp < 230`, behalve de 4×1-sizeclass) een afzonderlijke 2×2-presentatie met klein logo en refresh, twee kleine rijen, ratio en een footer. Gebruik een in de UI-state expliciet **betrouwbaar vastgestelde city** voor de tweede regel, in plaats van afgekapt volledig adres. Dit volgt de productprioriteit bezetting > volledige adresregel op kleine schermen.

In de 4×1-renderer verhuist `+N` bij meer geselecteerde favorieten naast het logo; de korte bronstatus onder de ratio blijft altijd zichtbaar, ook wanneer niet alle favorieten passen. Bij onbekende telling is dit `Onbekend`, bij historische geldige telling `Laatst bekend`; beide mogen nooit als verse beschikbaarheid gepresenteerd worden.

Tests dekken compact stadslabel, verdeling van onbekende en laatst bekende waarden en 4×1-versheidslabel af. Fysieke Samsung-test na succesvolle CI: breedte 2×2, terug naar 4×2, 4×1 met 1 én 2 geselecteerde favorieten; leesbaarheid, refresh, tap-intent en cache.

---

## Derde Samsung-feedback: één favoriet op 4×1

De nieuwe Samsung-screenshot bevestigt herstel van **alias/adres/plaats**, groen `2/2` en bronversheid voor twee geselecteerde favorieten. Nieuwe eis: bij **één geselecteerde favoriet** moet de widget naar **4 kolommen × 1 rij** verkleind kunnen worden en leesbaar blijven. Daarom: `appwidget-provider` ondersteunen `minResizeHeight=52dp` (default 4×2), `SizeMode.Responsive` uitbreiden met 260×62dp, en een specifieke horizontale samenstelling. Klein ChargeWatch-symbool, twee regels favorieteninformatie, groen/rode ratio, compacte leeftijd of `Laatst bekend`/`Onbekend` en afzonderlijk refreshicoon. De grote titel en footer passen niet in 4×1 en verdwijnen daar. Als een widget met meerdere favorieten tot 4×1 wordt verkleind, uitsluitend de eerste favoriet + `+N meer` tonen (geen stilzwijgende wijziging van selectie). Eén APK; huidige favorieten, cache en updatecoordinator ongewijzigd.

Kleinste rijhoogte wordt **niet gegarandeerd** door Android zelf: Samsung-launchergrid en lettergrootte fysiek testen, met 4×1 en terug naar 4×2, klikactie en tekstoverflow. Gerichte unittests voor responsive keuze en bronversheid toegevoegd. PR #112 blijft draft.

---

## Tweede Samsung-feedback: concrete adresregels + betrouwbaarheidscache

De gebruiker bevestigde opnieuw te kleine/onjuiste presentatie: nog geen tweede adresregel en de twee eerder groene `2/2`-waarden zijn `?`. **Gewenst:** alias + volledig adres en plaats op de tweede regel; zonder alias adres boven en plaats onder. De `FavoriteLocation`-objecten bevatten op de kaart niet gegarandeerd de stad. Haal uitsluitend bij ontbrekende stad (maximaal 5–10 geselecteerde favorieten, throttled) `address` en `city` op van het **bestaande** GET `/api/v1/locations/by-ndw-id/{id}`, sla deze lokaal in widget-DataStore op en behoud reeds opgeslagen alias/volgorde. Extra lookup is *uitsluitend metadata*, nooit bron voor verzonnen beschikbaarheidscijfers. Alias: naam + `adres, plaats`; geen alias: adres + `plaats`. Zonder betrouwbare plaats geen gok.

Verbeter cache-merge zodat een `Safe`/`Estimated`-snapshot met `0..total` alleen door een **andere betrouwbare** snapshot kan worden vervangen. Een ambigue/malforme nieuwe respons zorgt voor `Laatst bekend` en oorspronkelijke leeftijd; nooit beschikbare cijfers wissen of feedtijd onterecht verversen. Nieuwe tests voor deze regressie en adrespresentatie. Ruimteverdeling en hogere tekst/badges aangepast; de originele gekozen twee favorieten blijven zichtbaar bij 4×2. **PR #112 blijft draft; wacht op verse groene CI en fysieke Samsung-goedkeuring.**

## Samsung-bijsturing (2 november 2026)

- Screenshot van een daadwerkelijk geïnstalleerde widget met twee bewust gekozen favorieten: correcte groene `2/2`-waarden en bronversheid, maar letter-/badgeformaat te klein en onnodig veel lege ruimte.
- Namen en groene/rode `vrij/totaal`-badges worden groter; plaatsnaam als tweede regel met kleinere, contrasterende tekst. Haal die uitsluitend uit `FavoriteLocation.address` of het reeds lokaal opgeslagen adrestitle (zoals `Schietspoel 18, Oss`). Als een plaatsnaam niet betrouwbaar uit de komma-gescheiden adresinformatie volgt, laat de tweede regel weg. Bij geen alias wordt de stad niet ook in de straatregel herhaald. Geen geocoder, backendverandering of extra request.
- Bronversheid wordt aan de onderkant van het widgetoppervlak uitgelijnd.
- Nieuwe uitleesbare rijhoogte betekent dat 2×2/4×2 minder rijen kunnen tonen dan de oorspronkelijke voorlopige richtgetallen. We vergroten responsive hoogtematen zodat maximaal vijf zichtbaar blijven op voldoende hoge widgets. Tekst en ratio worden nooit tot onleesbaarheid geschaald.
- Pas dezelfde naam-/plaatsweergave toe op de configuratie-preview; voeg regressietests toe voor plaatsnamen met/zonder alias, ontbrekende plaats, Nederlandse postcode/land en widgetrijcapaciteit. CI en nieuwe Samsung-test vereist; geen merge tot apart akkoord.

## Definitieve minimalistische UX

| Android-gridrichtmaat | Zichtbare rijen | Inhoud |
| --- | --- | --- |
| Compact 2×2 | Tot 2 | Titel, refresh, naam + gekleurde `vrij/totaal`, beknopte versheid |
| Overzicht 4×2 | Tot 3 | Dezelfde velden, iets langere namen |
| Uitgebreid 4×3 | Tot 5 | Meer rijen, **niet** meer datacategorieën |

Dit zijn **richtmaten**: daadwerkelijke `DpSize` na resizing, launcher-padding, font scale en toegankelijkheid bepalen hoeveel rijen veilig zichtbaar zijn. Bereken capaciteit op de widgetruimte; toon nooit afgesneden ratios; bij overloop `+N meer`. De eerste zichtbare rijen volgen de **per-widgetvolgorde**, niet dynamische bezetting of afstand.

**Presentatie:**
- Material-achtige surface, afgeronde hoeken, ChargeWatch-logo/naam bovenaan, compacte refreshactie rechts, helder uitgelijnde statusbadges rechts. Geen sterren naast rijen: alle items zijn al favorieten.
- Bestaande kleursemantiek uit `ui/theme/Theme.kt` als basis: `AvailableGreen`, `NoneAvailableRed`, `FavoriteAmber`, en toegankelijke light/dark-varianten. Kleur wordt altijd gecombineerd met tekst `x/y` of `?` en een versheidswaarschuwing.
- Lees de bestaande instelling `appearance` (`system` / `light` / `dark`) en pas widgetthema aan bij appinstellingen, systeemthema en relevante widgetupdate; geen aparte thema-instelling voor widgets.
- Eigen naam (`alias`) gaat vóór de adresnaam; ellips uitsluitend in naam, nooit in ratio. Grote tekst mag de hoeveelheid rijen verminderen.
- Onderaan compacte, betekenisvolle feedcontrole-/cachetekst: `Bron gecontroleerd HH:mm`, `Deels verouderd`, `Laatst bekend · N min geleden` of `Laatste controle onbekend`. De footer mag **niet** één tijd presenteren als alle rijen verschillend oud zijn; zo nodig per rij waarschuwingsicoon en veilige globale aanduiding.

**Configuratie bij toevoegen en later via herconfiguratie:**
1. Eén scrollbare Activity met live preview van actuele formaatklasse; geen stappenwizard.
2. Lijst uit `FavoritesRepository.favorites` (DataStore), max. vijf selecteren, standaard eerste 2–5 uit de bestaande volgorde; up/down of toegankelijk reorder voor de **eigen widgetvolgorde**.
3. Opslaan schrijft alleen een lokale configuratie voor `appWidgetId`, maakt widget zichtbaar en vraagt initial refresh.
4. Meerdere widgets blijven onafhankelijk; een hernoemde favoriet verandert overal mee; verwijderde favoriet wordt uit widgetselecties verwijderd en de resterende rijen schuiven op.
5. Bij geen favorieten: `Nog geen favorieten` plus actie naar ChargeWatch/Favorieten. Bij geen geldige selectie: `Widget instellen` en actie naar configuratie. Geen crash bij nul rijen.
6. Reconfigureerbaarheid via de Android AppWidget-configuratie; daadwerkelijke beschikbaarheid in One UI fysiek toetsen.

**Klikacties:**
- Rij: expliciete `featureId` naar `MainActivity`; open dezelfde `LocationSelectionSheet` als bij een favoriet in de app, **direct volledig** met acties Details / Navigeren / Alternatieven in de buurt.
- Titel: kaartstartscherm. Refreshicoon: gecontroleerde handmatige update. Configuratie: via widgetreconfigure/instelling (indien gekozen in de grote variant).
- Iedere rij krijgt een **unieke** PendingIntent-identiteit (data-URI of requestcode gekoppeld aan widget-ID en feature-ID), zodat Android geen verschillende locaties verwisselt.
- Cold start, reeds draaiende app (`onNewIntent`) en verwijderd/onvindbaar ID: route veilig afhandelen, nooit naar een andere locatie of automatisch naar eerste favoriet springen.

## Bestaande code op main (gecontroleerd 2 november 2026)

| Bestand / contract | Waarneming en gevolg |
| --- | --- |
| `data/favorites/FavoritesRepository.kt` | DataStore `chargewatch_favorites` met serialiseerbare `FavoriteLocation(featureId,title,lat,lon,address,operatorName,alias)`; `Flow<List<FavoriteLocation>>` en rename/move/remove. Geen tweede favorietenopslag. |
| `data/favorites/FavoritesAvailabilityClient.kt` | `refresh(favorites)` doet gezamenlijke viewport voor nabijgelegen favorieten, anders afzonderlijke kleine viewports parallel; ChargeWatch API eerst, NDW-fallback; `Success` of `Partial`. Hergebruik, maar cache individuele successen. |
| `data/favorites/FavoriteAvailability.kt` | Onbekend bij ontbrekende/ambigue telling; aparte feedwaarneming versus `lastUpdated` van een object. Status en versheid blijven gescheiden. |
| `data/favorites/FavoriteFetchPolicy.kt` | Twee minuten recent-opgehaald-beleid **in app-geheugen**; voldoet niet als persistente widgetcache. |
| `ui/ChargeWatchApp.kt` | Favorieten/statussen nu in Compose-state, `favoriteDetail` opent bestaande `LocationSelectionSheet`, aanvankelijk uitgebreid. Route vanuit externe Intent ontbreekt. |
| `MainActivity.kt`, manifest, Gradle | Geen widgetprovider, configuratie-activity, Glance- of WorkManager-dependency. Bestaande `minSdk=26`, `targetSdk=36`. |
| `data/ndw/ChargeWatchMapClient.kt` | GET `/api/v1/map/viewport?bbox=...`, expliciete validatie van volledige respons. Geen widget-backend nodig. |
| `ui/theme/Theme.kt` | Reeds gedefinieerde groen/rood/neutraal-kleuren en light/dark styling. |

## Gegevensbetrouwbaarheid — bindend

1. **NDW/OCPI is bron van waarheid**; widget introduceert nooit eigen bezettingsberekening of gefingeerde `0/y`. Ratio telt **EVSE's**, niet stekkers.
2. Beschikbare aantallen + betrouwbare mapping: `available > 0` geeft **groen**, `available = 0, total > 0` **rood**. Een oud `lastUpdated`/brontimestamp maakt deze correcte aantallen **niet grijs**; de statuskleur blijft groen/rood.
3. Ambigue/ontbrekende/ongeldige aantallen of een daadwerkelijk niet-betrouwbare feed/response worden `?`, met korte oorzaak. Onderscheid `sourceUpdatedAt` (objectwijziging), `feedLastObservedAt` en `feedIsStale` (feedcontrole) en `fetchedAt` (laatste succesvolle toestelrequest). Alleen metadata-overleving is geen bewijs van verse status. Behoud bij een oudere maar inhoudelijk betrouwbare feedwaarneming de bekende ratio **met expliciete verouderingsindicatie**, nooit als live garantie.
4. Als een netwerkupdate mislukt: bestaand **valide** cache-item behouden mét originele bronwaarneming én ophaaltijd; toon zichtbaar `Laatst bekend` en leeftijd. Geen oude data stilzwijgend opnieuw als vers markeren. Na opslagverval/corruptie/nooit opgehaald: `?`.
5. **Partial succes:** vervang alleen werkelijk ontvangen gevalideerde favorietrecords; andere records houden hun eigen tijd en waarschuwing. Niet aangetroffen in een verder volledige viewport = `niet gevonden`, niet `0 vrij`. Onvolledige/afgekorte viewportresponses worden niet als compleet geaccepteerd.
6. Geen `?` alleen omdat een laatst gewijzigd bronobject oud is. Geen toon-als-actueel enkel op basis van een succesvolle HTTP-call. **De semantiek van app, kaart en widget moet dezelfde functies/regels delen**; gerichte regressietests dekken het goedgekeurde #109 kleurgedrag af.

## Implementatiearchitectuur (Android-only)

**A. Native widget**
- Voeg Jetpack Glance AppWidget + `GlanceAppWidgetReceiver`, `appwidget-provider` XML en configuratie-Activity toe aan de bestaande `android/app` module. `SizeMode.Responsive` of geschikte maatklasse, `AppWidgetManager` resize-callbacks; render uitsluitend lokaal UI-model en netwerk **nooit** direct vanuit composable.
- `WidgetUiState` bevat per geselecteerde rij: id, displayName, statusenum, `available/total` indien betrouwbaar, `feedObservedAt`, `fetchedAt`, warning en render-/huidige tijd. Een pure mapper stelt status/tekst/kleuren afgeleid vast.

**B. Lokale widgetconfiguratie**
- Kleine DataStore onder applicatiecontext; serialiseer `WidgetConfiguration(appWidgetId, selectedFeatureIds ordered)`. Houd schema-versie en defensieve decode, opruimen bij verwijdering van widgetinstantie, en reconciliatie als favoriet verwijderd is.
- Alias/coördinaten **niet dupliceren in config**: los `featureId` op via `FavoritesRepository`. Widgetinstantie-IDs nooit als duurzame gebruikersidentiteit op server.
- Naast configuratie een kleine persistente availability-snapshotcache, per `featureId` (getallen, reliability, feed metadata, fetchedAt, provenance). Persistente DataStore volstaat voor beperkte set; pas bij concreet volume-/transactierisico Room heroverwegen.

**C. Eén gedeelde updater**
- `WidgetRefreshCoordinator` vraagt de **unie** van geselecteerde `featureId`s voor alle widgetinstanties op, dedupliceert overlappende aanvragen, gebruikt `FavoritesAvailabilityClient`, schrijft geldige resultaten atomair per item en vraagt daarna `updateAll` of gericht widgetrender aan.
- WorkManager unieke periodieke achtergrondtaak met **minimaal 15 minuten**, `NetworkType.CONNECTED` en battery-/Doze-vriendelijke **best-effort** uitvoering; interval is geen live SLA. Initiële/handmatige/favorietmutatie-updates via unieke one-time work of gedeelde coordinator; een korte throttle (voorstel 60 seconden) tegen herhaald tikken zonder bestaande oude cache te verjongen.
- WorkManager herstelt persistent ingeplande taken bij reboot; widgetprovider reageert op widget toevoeging/verwijdering, update en resize. Geen aparte BOOT_COMPLETED-loop of pollingservice tenzij tests dit aantoonbaar vereisen. Cancel periodieke work bij verwijdering van de laatste widget.
- Geen nieuw backendendpoint: uitsluitend bestaande publieke read-only API, met al aanwezige NDW-fallback en rate-limit-waarschuwingen. Bij offline render cached data direct, niet blokkerend wachten op requests.

**D. App-deeplink**
- Expliciete Intent met `featureId`, widget-ID en action type naar bestaande `MainActivity`; `onCreate` én `onNewIntent` omzetten naar één consumable navigatie-event. Resolve bij voorkeur de bijgewerkte favoriet of valide cached `MapLocation`; indien nodig één gericht beschikbaarheidsverzoek. Open bestaande full `LocationSelectionSheet` op de juiste locatie.
- Geen algemene webdeeplink nodig in v1. Controleer PendingIntent-mutability, requestcode/data uniqueness, `exported` en veilige inputvalidatie.

## Implementatiestappen na expliciet codeakkoord

1. Introduceer widgetschema/configuratie en persistente availabilitycache; pure statusmapper en gerichte unit-tests.
2. Implementeer gedeelde updatecoordinator (WorkManager, fetch/dedupe, cache, partial-fail/reboot/cleanup) en Glance-renderer voor drie maatklassen.
3. Configuratie-Activity met live preview, lokale selectie/volgorde, bewaren en reconfigureren; koppel wijzigingen uit bestaande favorieten.
4. Widgetclickroute naar bestaande detailsheet, inclusief cold-start en reeds actieve app.
5. Tests (minimaal): `0/y`, `x/y`, `?`, bronrecord oud vs feed vers, feed onbetrouwbaar, offline laatste stand, per-favoriet partial fail, 0/1/5 selectie, wijziging alias/volgorde/delete, twee widgetinstanties, resize, unique click IDs, throttle/dedupe.
6. Android Gradle unit-tests en debug-APK CI; fysieke Samsung Galaxy S23 Ultra: 2×2/4×2/4×3, thema, grote letters, widget toevoegen/reconfigure/verwijderen, reboot, vliegtuigmodus, API-uitval/fallback, handmatige/periodieke refresh, klik bij cold start en warm start.
7. Zelfstandige featurebranch `feature/android-favorites-widget` vanaf dan actuele main, kleine gerichte commits, update van `docs/STATUS.md`, issue #101 en PR met exact bewijs; **geen squash-merge zonder apart expliciet akkoord**, en geen NAS-deploy nodig tenzij later afzonderlijk besloten.

## Volgende menselijke beslissing

**Uitvoering door gebruiker geautoriseerd op 2 november 2026.** De technische ontwerpkeuzes zijn binnen #101 in PR #112 geïmplementeerd/in uitwerking. Eerst CI, eventuele correcties en fysieke Samsung-test; daarna expliciet apart mergeakkoord vragen.

## Afzonderlijke roadmapwens: Android Auto

[Onderzoeksissue #110](https://github.com/VanHinthem/ChargeWatch/issues/110) is **niet onderdeel van #101**. Android for Cars POI-templates ondersteunen laadlocaties en navigatie naar een externe navigatie-app via `CarContext.ACTION_NAVIGATE`. Google kondigde in mei 2026 bovendien toekomstige ondersteuning voor mobiele widgets in Android Auto aan; daadwerkelijke uitrol, geschiktheid, acties en toestel-/auto-compatibiliteit eerst testen. Zie `docs/ROADMAP.md`.

## Hervatting PR #112 — 5 november 2026: batch-API en gedeelde cache

**Dit is de actuele implementatierichting; eerdere verwijzingen hierboven naar geografische viewportverversing, directe NDW-fallback en een losse detail-GET voor plaatsnaam zijn historisch en niet meer de actieve widgetdatastroom.**

- Bestaand ontwerp, instellingen en resizable Glance-layouts (4×1, 2×2, 4×2, 4×3) blijven behouden.
- PR #112 is op de huidige `main` gebracht, inclusief de inmiddels gemergede API PR #124, gzip PR #125 en Android-cache PR #123. Widgetreceiver en configuratieactiviteit worden weer in de APK/Manifest opgenomen.
- **Nieuwe datastroom:** `WidgetRefreshCoordinator` kiest exact de bewaarde favoriete `featureId`-waarden en gebruikt `SharedLocationDetails.cache.loadDetails(ids, force)`. De `LocationDetailsCache` splitst groepen automatisch in maximaal twintig ID's en vraagt de bestaande `POST /api/v1/locations/by-ndw-id/batch` aan met `includeStatistics=false`.
- De widget en de kaart/favorieten-sheets gebruiken dezelfde **in-process** details-cache met een 2-minuten-TTL. Na process death is deze cache leeg; dat is verwacht. `WidgetStore` blijft een afzonderlijke **persistente, kleine last-known cache** voor de zichtbare EVSE-ratio's en adres/plaats, zodat offline en procesherstart geen nep-0 of ontbrekende eerdere betrouwbare ratio veroorzaken.
- De batchresponse bevat ook de canonieke `city`; de client projecteert deze naast de `MapLocation`. Één atomaire DataStore-commit verwerkt EVSE-counts én adres/plaats. Er is geen tweede GET per adres meer; de oude `WidgetAddressClient` is verwijderd.
- Geen NDW/viewport-fallback voor de widget. Een mislukte batch bewaart de eerdere betrouwbare status en markeert deze als *Laatst bekend*. `NOT_FOUND` of een ambigue telling overschrijft nooit een eerder betrouwbare groen/rood-ratio.
- Handmatig verversen omzeilt de detail-TTL; automatische WorkManager-verversing blijft Android best-effort en minimaal om de vijftien minuten. Veranderingen in lokale selectie en aliases komen via de bestaande reactieve DataStore/Glance-flow meteen in de widget.
- Een widgettap opent met het exact geselecteerde `featureId` de bestaande uitgebreide sheet. Als de details nog in de gedeelde cache zitten, worden ze direct hergebruikt; anders volgt uitsluitend een by-ID batchrequest. Historische gegevens worden door dezelfde sheet op aanvraag opgehaald uit de afzonderlijke statistiekcache (15 minuten).
- De API/database/NAS worden in deze PR **niet** gewijzigd. De NAS draait volgens de gebruiker de samengevoegde batch/gzip-API. Gzip over publiek HTTPS is op 5 november bevestigd (`HTTP/2 200`, `Content-Encoding: gzip`, `Vary: Accept-Encoding`).

**Nog open voor acceptatie:** actuele Android CI + fysieke Samsung-test van widgetregistratie/toevoegen, formaat 4×1/2×2/4×2, selectie en alias, betrouwbare groen/rood-aantallen, handmatige snelle refresh, correct openen van details, en offline/last-known. PR #112 blijft draft en ongemerged tot expliciet akkoord.
