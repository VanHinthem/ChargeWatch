# ChargeWatch — actuele roadmap

**Bijgewerkt:** 6 november 2026, na merge van [PR #131](https://github.com/VanHinthem/ChargeWatch/pull/131) en v1-besluit optie A voor #103.  
**Actuele implementatiestatus:** [STATUS.md](STATUS.md).  
**Oorspronkelijke fasen, metingen, checklists en ontwerpbesluiten:** [historische roadmap](archive/ROADMAP_THROUGH_2026-11-05.md) en [historisch statuslog](archive/STATUS_THROUGH_2026-11-05.md). De oude checklists van fases 0–2 zijn geen nieuwe werkvoorraad.

## Productdoel v1

Een Android-first laadpaalassistent met landelijke laadlocaties, betrouwbare actuele `vrij/totaal`-status, huidige tarieven, adres-/GPS-zoeken, filters, nabije alternatieven, lokale favorieten en native widgets. De API is read-only, NDW/OCPI is bron van waarheid; geen accounts, betalingen of pushnotificaties in deze release. Historische beschikbaarheid en betrouwbaarheid zijn expliciet als experimentele EWMA-indicaties opgenomen, **niet** als gekalibreerde voorspellingen of bewezen uptime. Echte 90-daagse uptime is buiten v1 geplaatst.

## Afgeronde v1-onderdelen

- **Datafundament:** PostgreSQL/PostGIS, landelijke import, read-only .NET API en tarief-/EVSE-datakwaliteitscorrecties. Het oude data-first ontwikkeltraject is afgesloten.
- **Android P1:** kaart/PDOK-bestemming zoeken, filters, GPS, vijf nabije laadlocaties en alternatieven ([#95](https://github.com/VanHinthem/ChargeWatch/issues/95), [#99](https://github.com/VanHinthem/ChargeWatch/issues/99) gesloten).
- **Android P2:** native Glance-favorietenwidgets met variabele grootte, betrouwbare kleur en `vrij/totaal`, lokale selectie/alias en refresh. [#101](https://github.com/VanHinthem/ChargeWatch/issues/101) gesloten, [PR #112](https://github.com/VanHinthem/ChargeWatch/pull/112) gemerged; fysieke Samsung-acceptatie geslaagd.
- **Android P3:** gecombineerde historische grafiek en EVSE-/locatiebetrouwbaarheid op de bestaande experimentele EWMA, [API-PR #130](https://github.com/VanHinthem/ChargeWatch/pull/130) en [Android-PR #131](https://github.com/VanHinthem/ChargeWatch/pull/131) gemerged, Android op Samsung geaccepteerd. [#103](https://github.com/VanHinthem/ChargeWatch/issues/103) afgerond binnen v1-keuze optie A; exact rolling-90-day-uptime en verdere kalibratie naar [#132](https://github.com/VanHinthem/ChargeWatch/issues/132).
- **Locatie-dataflow implementatie:** landelijke overview- en clusterlogica [PR #117–#122](https://github.com/VanHinthem/ChargeWatch/pulls?q=is%3Apr+is%3Amerged+113), batch-API [#124](https://github.com/VanHinthem/ChargeWatch/pull/124), HTTP-gzip [#125](https://github.com/VanHinthem/ChargeWatch/pull/125), gedeelde tweeledige Android-cache [#123](https://github.com/VanHinthem/ChargeWatch/pull/123). **Productieactivering en landelijke Android-kaart afgerond op 6 november na NAS HTTP-metingen, Samsung-acceptatie en [PR #127](https://github.com/VanHinthem/ChargeWatch/pull/127).**

## Nog te doen vóór een v1-release

| Spoor | Volgende concrete stap | Done wanneer |
|---|---|---|
| **[#104 — Android P4: v1 releasekwaliteit](https://github.com/VanHinthem/ChargeWatch/issues/104)** | Integraal testen (schermen, widget, offline, donker/licht, toegankelijkheid, performance, energie, signing, privacy/attributie en API-beveiliging) | Releasechecklist compleet en expliciet distributie-/releaseakkoord |

**Functionele prioriteit:** #104 is de resterende v1-kwaliteits-/releasegate. #103 is conform expliciet optie A-besluit afgerond: EWMA-indicaties blijven experimenteel, geen 90-daagse uptimeclaims of nieuwe opslag voor v1. Nieuwe NAS-deploy, featureflagwijziging en publieke Android-release alleen met afzonderlijk akkoord.

## Uitgesteld, geen releaseblocker

- **[#132 — EWMA-initialisatie, publicatiecriteria en optionele 90-daagse uptime](https://github.com/VanHinthem/ChargeWatch/issues/132):** na v1 onderzoeken, niet stilzwijgend opnieuw tot v1-releasegate maken.

- **[#110 — Android Auto](https://github.com/VanHinthem/ChargeWatch/issues/110):** onderzoek naar favorieten en veilige navigatie in de auto. Nog geen implementatiebesluit.
- **[#79 — datakwaliteitsdashboard](https://github.com/VanHinthem/ChargeWatch/issues/79):** historische monitoring en bronintegriteitsrapportages. Voor nu niet prioriteren.
- **1×1-widget:** toekomstig idee vanuit Samsung-formaattests, geen bestaande aparte launchermaat of goedgekeurd feature-issue.
- Geavanceerde kortetermijnvoorspellingen, notificaties, accounts en betalingen blijven buiten v1.

## Administratieve afsluiting 5–6 november

- [#101](https://github.com/VanHinthem/ChargeWatch/issues/101) gesloten na [#112](https://github.com/VanHinthem/ChargeWatch/pull/112).
- [PR #54](https://github.com/VanHinthem/ChargeWatch/pull/54) zonder merge gesloten; de losse Geffen-dekkingsnotitie is niet langer nodig.
- [#103](https://github.com/VanHinthem/ChargeWatch/issues/103) **afgerond binnen optie A**: bestaande EWMA-historie voor v1; API-PR #130 en Android-PR #131 gemerged, Samsung-acceptatie. Verdere analyse in #132.
- [#113](https://github.com/VanHinthem/ChargeWatch/issues/113) **afgerond**: overview-featureflag actief, NAS HTTP-metingen en beperkte concurrency geslaagd (5 requests/scenario; geen statistisch p95-bewijs), `/cell`-pagina geverifieerd, Samsung-acceptatie inclusief bijgewerkte APK bevestigd, standaardkaart en verwijderde testinstelling via PR #127 gemerged.

## Besluit- en verificatieprincipes

Behoud bestaande correcte `vrij/totaal`-semantiek (groen/rood, of expliciet onbekend) en betrouwbare bronversheid bij cache/offline. Niet-verifieerbare historische berekeningen zijn geen voorspellingen. Maak gerichte PR's met API-/Android-tests, meet nieuwe risico's met representatieve echte data en vraag voor merges, NAS-wijzigingen en release expliciet toestemming.
