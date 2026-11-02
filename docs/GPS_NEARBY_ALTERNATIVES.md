# Issue #99 — GPS en nabije alternatieven (vastgesteld 2 november 2026)

## Productbesluiten

GPS-optie A en alternatieven-optie A zijn goedgekeurd. Zie [issue #99](https://github.com/VanHinthem/ChargeWatch/issues/99). De bestaande kaart, de PDOK-pin, de 25km-nearest-query en de vijf onafhankelijke filtergroepen blijven behouden.

### GPS

- Zwevende *Mijn locatie*-knop rechts, buiten de zoekheader en boven de tabnavigatie. Eerst bij de gebruikersactie Android-locatiepermissie vragen (fine + coarse), en beperkte/coarse toestemming accepteren. Geen foreground-service, background-locatie of continue tracking.
- Vraag één recente locatie op met timeout. Toon nauwkeurigheid/geschatte positie en een onderscheidende blauwe GPS-stip, los van de bestaande PDOK-bestemmingspin en de laadmarkerclusters. Gebruik niet stilzwijgend een oude fix als live locatie.
- Na een GPS-actie maximaal vijf nearest binnen 25 km opvragen, met de actuele filters. De camera past eenmalig GPS + maximaal vijf locaties binnen de bestaande 11–16.5 zoomgrenzen en search-headerpadding. Nearest-paneel standaard ingeklapt.
- Handmatig pannen/zoomen, resultatenpaneel openen/sluiten en terug-naar-overzicht veroorzaken geen nieuwe nearest-queries of autofit. Herhaald drukken op GPS mag een nieuwe fix en nieuwe nearest-query ophalen.
- Weigerings- of locatie-uit-fallback zonder dat PDOK of gewone kaartbediening stilvallen. GPS-coördinaten gaan bij nearest-zoekactie via HTTPS naar de ChargeWatch API, maar worden niet in accounts of persistente opslag bewaard.

### Alternatieven

- Vanuit de kaartdetails en vanuit een favoriet: *Alternatieven in de buurt*. Sluit details, open de kaart, zoek binnen 25 km rond de oorspronkelijke laadlocatie; sluit oorspronkelijke OCPI featureId uit en toon maximaal vijf alternatieven op hemelsbrede afstand.
- Neem uitsluitend de **huidige actieve kaartfilters** over (geen automatisch afgeleide stekkers, vermogen of operator). Gebruik maximaal zes kandidaten wanneer de huidige locatie in top-vijf kan zitten; neem de eerste vijf resterende in API-afstandsvolgorde. Lege en gedeeltelijke lijsten zijn geldig.
- Het paneel is standaard ingeklapt; de oorspronkelijke laadlocatie blijft als referentie in het overzicht zichtbaar. Tik op resultaat centreert alleen kaart, niet de detailsheet. Terug naar vorig overzicht is contextwissel; doelwit-icoon herstelt alleen de camerafit.
- Status is locatiebreed, niet per gekozen connector. Toon verouderde/unknown bron niet als vers/zeker vrij; een actieve minFree-parameter blijft door de backend gematcht volgens bestaande filtercontract.

### Overzicht/terugnavigatie

Eén actieve nearby-context: `Address`, `Gps` of `Alternatives`. Sla de vorige context op voor expliciete terugactie, zonder onbegrensde navigatiestack. PDOK-query en bestemmingspin bewaren wanneer GPS/alternatieven actief worden; het wissen van de PDOK-query verwijdert alleen het bewaarde adres. De actieve titel is contextafhankelijk; de oude PDOK-query wordt niet als zoekheadertekst weergegeven terwijl GPS of alternatieven actief zijn. Filters blijven sessie-only en gelden voor kaart en actief overzicht. Een nieuwere request/context/filtercombinatie maakt oudere near-responses ongeldig.

### Instellingen

Voeg een sectie **Locatie** toe met `Mijn locatie gebruiken` (standaard aan, DataStore/voorkeuren) en `Android-locatietoestemming` met actuele status `Niet toegestaan`, `Geschatte locatie` of `Precieze locatie`. De tweede rij opent de systeem-appinstellingen. Bij uitschakelen verdwijnen GPS-overlay en eventuele GPS-resultaten/fix; PDOK en alternatieven blijven functioneren. De appschakelaar verleent/trekt geen OS-permissies in en activeert niet zelfstandig GPS op de achtergrond.

### Acceptatie

- Test op fysieke Samsung: permissie fine/coarse/deny/permanent deny, GPS uit, timeout, stale/unknown, offline; geen verborgen hergebruik van oude posities.
- Test GPS vijf-nearest, 0/1/5 resultaten, filterwijzigingen, contextwisselingen PDOK ↔ GPS ↔ alternatieven, kaart pan/zoom en terugnavigatie zonder onnodige queries.
- Test alternatieven vanaf kaart én favoriet en geen oorspronkelijke locatie tussen resultaten; geen dubbele sheet en correcte markersemantiek.
- Android tests en build/CI aantoonbaar uitgevoerd; `docs/STATUS.md` mergeklaar vóór definitief PR-reviewverzoek. Geen backendwijzigingen, merge of deploy zonder expliciet akkoord.

## Implementatienotities — PR #109

- Android `DeviceLocationProvider` gebruikt `LocationManager` zonder nieuwe Play Services-afhankelijkheid: foreground fine/coarse-permissies, eenmalige GPS-fix en een verse netwerk-fallback, maximumleeftijd van een locatie-fix 120 seconden.
- De lokaal onthouden schakelaar is een private Android `SharedPreferences`-voorkeur (geen account, GPS-coördinaten of permissiehistorie in persistente opslag). Android-toestemming blijft onafhankelijk en wordt bij terugkeer naar de app opnieuw gecontroleerd.
- `NearbyContext` scheidt Address/Gps/Alternatives en houdt het geselecteerde PDOK-adres apart. De nearest-resultaten worden maximaal twee minuten in sessiegeheugen gecachet voor snelle contextwisselingen; oudere beschikbaarheidsresultaten worden niet onbeperkt hergebruikt.
- Bestaande ChargeWatch API, actieve filtercontracten, locatiebrede aantallen en bronversheid blijven de enige bron voor de lijst. Geen nieuwe backendserver, tarieven per lijstitem of automatische koppeling aan voertuig-/connectorprofielen.
- Acceptatie op fysiek toestel omvat de Android-permissiedialogen, instellingen, GPS uit, nauwkeurigheid, camerafit en kaartoverlays. Unit-tests en Android-CI zijn aparte technische controles; groen CI is geen Samsung-goedkeuring.
