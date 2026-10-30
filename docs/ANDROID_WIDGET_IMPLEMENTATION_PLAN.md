# Android-widget — technisch startontwerp (issue #100)

**Status:** voorstel voor technische uitwerking; nog geen widgetcode geïmplementeerd of goedgekeurd voor merge. Afspraken uit [ANDROID_DESIGN.md](ANDROID_DESIGN.md) en de opgeslagen [widgetshowcase](design/ChargeWatch%20Android%20Widget%20Showcase.png) / [widget-UX-board](design/ChargeWatch%20Android%20Widget%20UX%20Board.png) hebben voorrang op technische voorbeelden hieronder. Geen nieuwe backend-, NAS- of databasecomponent vereist.

## Doel en afbakening

- Native Android-widget die de gebruiker vanuit bestaande **lokale favorieten** configureert, en die betrouwbare beschikbaarheid in één oogopslag geeft.
- Eerder overeengekomen Glance-formaatvarianten: **compact 2×2**, **overzicht 4×2** en **uitgebreid 4×3**, waarbij de werkelijke Android-resize-afmetingen maatgevend zijn. De configuratie ondersteunt in principe **2–5 gekozen favorieten per widgetinstantie**. Als afmetingen/tekstschaling minder tonen, kies een begrijpelijke begrensde weergave zonder statusgegevens af te knippen.
- Per widgetinstantie eigen keuze/volgorde; configureerbaar tonen/verbergen van historische mini-grafiek (standaard uit in compact, in groot alleen bij voldoende ruimte **én gevalideerde brondata**), optionele afstand/versheid waar mogelijk.
- Tik op een favoriete locatie opent **ChargeWatch met die locatie**, gevolgd door de afgesproken acties **Details / Navigeren**. Herstart, stale status of ontbrekende API-respons mag niet naar een willekeurige locatie navigeren.

## Hergebruik uit main (code gecontroleerd 30 oktober 2026)

| Onderdeel | Bestaand contract | Widgetgebruik |
| --- | --- | --- |
| `FavoritesRepository` | DataStore `chargewatch_favorites`; `FavoriteLocation(featureId, title, alias, lat/lon...)`; sortering en wijzigingen als Flow | Eén bron voor favorieten, naam en volgorde; geen nieuwe cloudopslag |
| `FavoritesAvailabilityClient` | Batch van nabije favorieten via ChargeWatch-kaart-API, NDW-fallback, `Success`/`Partial` en waarschuwingen | Gecontroleerd hergebruiken voor widgetupdates; geen onbegrensde request-per-widget-polling |
| `favoriteAvailability` | `?` bij ontbrekend/onbetrouwbaar; feedwaarneming/`feedIsStale` voor versheid; niet object-`lastUpdated` | Zelfde status- en staleness-semantiek als Favorietenpagina |
| `favoritesNeedingFetch` | Hergebruik van recente appresultaten binnen twee minuten | Uitgangspunt voor deduplicatie; widgetupdates hebben daarnaast **persistente** cache/laatste-ophaaltijd nodig |

Android heeft nog geen AppWidget-provider/receiver of Glance-dependency: die horen bij de eigen implementatie-PR. Controleer de actuele Jetpack Glance / WorkManager-API tijdens implementatie.

## Voorgestelde Android-architectuur

1. **Glance AppWidget + Receiver** met responsive weergaven; de renderer ontvangt een klein UI-model met naam, `vrij/totaal` of `?`, bronwaarnemings-/ophaaltijd en de huidige statussoort. Bouw geen tweede statustellogica.
2. **Configuratie per `appWidgetId`:** geselecteerde `featureId`-waarden en volgorde, optionele grafiek- en versheidsinstellingen lokaal opslaan. Verifieer hernoemen, wissen, widget-resize, verwijderen, terugzetten en meerdere instanties.
3. **Gedeelde updater/repository:** haal alleen geselecteerde favorieten op, bundel netwerkverzoeken waar mogelijk, dedupliceer simultane refreshes en bewaar de laatst gecontroleerde waarden met expliciete `fetchedAt` en feedstempel. De widget toont die cache na process death of tijdelijk offline, **altijd voorzien van leeftijd/laatst-bekend-status**.
4. **Refreshbeleid:** bij configuratie, widget-refreshknop, relevante wijzigingen in de app en een platformvriendelijke periodieke update met Android-achtergrondbeperkingen. Onderzoek `WorkManager` en `AppWidgetManager`/Glance update-events; geen belofte van seconde-realtime of continue polling. Rate-limit en mislukte updates maken geen bestaande status 'nieuw'.
5. **Navigatie:** een expliciete `featureId`-doorgeefroute vanuit widget naar ChargeWatch, die de geselecteerde locatie opzoekt of de reeds opgeslagen lokale context gebruikt en een Details/Navigeren-actiesheet opent. Geen impliciete kaart-hercentrering naar 'eerste favoriet' als de ID ontbreekt.
6. **Presentatie en toegankelijkheid:** hetzelfde afgeronde oppervlak, kleursemantiek en bekende icoonstijl als de Android-app; contrast in beide thema's, grote tekst en minimum-tikoppervlakken. Statuskleur alleen is nooit voldoende; brontekst of vraagteken blijft zichtbaar.

## Data- en foutcontract

- `0/y` alleen bij betrouwbaar vastgesteld geen vrije EVSE; `?` bij ambigu, stale/unknown of onvoldoende gegevens. `lastUpdated` is geen bewijs dat de feed recent is gecontroleerd.
- Bij offline/fout: laatste bekende status *alleen* met expliciet label `Laatst bekend` en controleouderdom; geen 'actueel' op basis van cache. Bij onbekend of uitgevallen refresh geen verzonnen nulstatus.
- Bij geen geselecteerde favorieten: duidelijke configuratieactie. Bij verwijderde favoriet: verwijder hem uit widgetconfiguratie of toon expliciet dat hij niet meer beschikbaar is.
- Tarieven, accu-, locatie- of historische kanspercentages zijn geen vereiste voor de eerste werkende widget. Een grafiek alleen bij valide publiceerbare statistieken; anders weglaten of `Onvoldoende gegevens`.

## Acceptatie- en verificatieplan

- Gerichte unit-tests voor selectie-/volgorde-/verwijderlogica, statusonzekerheid, cache-staleness, update-deduplicatie en correcte `featureId`-navigatie.
- Android-CI en installeerbare debug-APK; fysieke Samsung-review van **2×2, 4×2, 4×3**, light/dark, resize, verschillende teksten en meerdere widgets.
- Toesteltests voor favoriet hernoemen/verwijderen, vliegtuigmodus, API-uitval/NDW-fallback, handmatige en periodieke refresh, navigatie via widget na cold start en app-standby.
- Vooraf per widgettype het gewenste aantal zichtbare favorieten en mini-grafieken volgens bestaande referentiebeelden toetsen. Niet mergen zonder expliciet gebruikersakkoord; geen NAS- of publieke-release-autorisatie.

## Werkvolgorde

1. Technische keuze widget-state/cache/update en navigatie finaliseren; bestaande visuele referenties op actuele toestelgrootte toepassen.
2. Kleine widget-MVP met betrouwbare compacte status; daarna grotere layouts/configuratie en eventueel valide mini-grafiek.
3. Echte Samsung-acceptatie en geverifieerde CI; afronden via PR en `docs/STATUS.md`.

**Afhankelijkheden:** issue [#100](https://github.com/VanHinthem/ChargeWatch/issues/100) is de eerstvolgende Android-feature. Statistiek/publicatiegates volgen via [#102](https://github.com/VanHinthem/ChargeWatch/issues/102); de widget mag niet op die toekomstige cijfers vooruitlopen.
