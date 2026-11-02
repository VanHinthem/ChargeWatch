# Android-startschermwidget — UX-contract en technisch ontwerp (#101)

**Status (2 november 2026):** minimalistische UX-richting A goedgekeurd en vervolgens expliciet akkoord gegeven voor Android-implementatie. Uitvoering op `feature/android-favorites-widget` in [draft-PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112). CI en Samsung-validatie nog te voltooien; **geen merge of deploy zonder afzonderlijk expliciet akkoord**. Canoniek issue: [#101](https://github.com/VanHinthem/ChargeWatch/issues/101). De oude documentatie-PR #108 (`docs/android-v1-next-steps`) heeft een conflicterende historische roadmap en is **niet** de implementatiebasis; de relevante punten zijn hier opnieuw tegen `main` beoordeeld.

## Doel en productgrenzen

- Eén **native Android-startschermwidget** binnen de bestaande APK en dezelfde app-module, met één responsive implementatie op basis van **Jetpack Glance**.
- In één oogopslag zien hoeveel EVSE's bij gekozen favoriete laadlocaties vrij zijn. Geen mini-kaart, tarief, connectorinformatie, afstand, historische minigrafiek, account, notificatie of eigen navigatie in deze eerste widgetversie.
- Bestaande **lokale** favorieten blijven de enige bron voor `featureId`, alias, adres/coördinaten en volgorde. Geen cloudopslag, write-endpoint, nieuwe locatiepermissie, GPS-tracking of NAS-/DB-wijziging.
- De gebruiker kiest **maximaal vijf favorieten per widgetinstantie**; als er minder dan twee favorieten bestaan, kan ook één worden getoond. Iedere instantie heeft eigen selectie en volgorde, los van de algemene favorietenlijst.
- Geen aparte APK of app: toevoegen/updaten van de bestaande APK installeert/bijwerkt ook widgetreceiver, configuratie en achtergrondtaak.

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