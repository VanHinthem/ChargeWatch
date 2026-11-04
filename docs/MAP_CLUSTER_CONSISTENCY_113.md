# ChargeWatch #113 — consistente clusterpresentatie en interactie

**Datum:** 4 november 2026  
**Status:** review op `fix/map-cluster-consistent-presentation-113`; nog niet gemerged of gedeployed.

## Geobserveerd op Samsung na PR #120

De kaart heeft geen zichtbaar raster meer. Wel tonen twee zoomniveaus verschillend gedrag:
- Groter gebied (bijv. Berghem / Uden): grijze clusterkern met **aantal locaties**, groen/rood/onbekend-ring (bijv. `419`, `268`, `168`).
- Lokale volledige viewport (bijv. Oss): groepen met **opgetelde laadpuntbezetting**, volledig groene/rode cirkel (bijv. `31/46`, `35/45`), terwijl alleen échte individuele laadlocaties `vrij/totaal` horen te tonen.

De oorzaak is het gebruik van twee afzonderlijke MapLibre-renderpaden met eigen presentatie, clustering en tapbeleid. Het onderscheid komt van het **datatype**, niet van de echte gebruikersinteractie.

## Vastgelegd gedrag in deze correctie

| Situatie | Weergave | Tik |
| --- | --- | --- |
| Cluster van meerdere laadlocaties, ongeacht bron of zoom | Grijze kern met **aantal laadlocaties**; ring: fractie met vrije EVSE / zonder vrije EVSE / onbekend op **locatieniveau** | Zelfde `markerTapAction`; +2 zoom tot zoom 18, dan volledig clusterkeuzepaneel |
| Eén laadlocatie | **Vrij/totaal** en groen/rood/onbekend volgens de bestaande `AvailabilitySummary.status`, inclusief eventuele lokale favorietster | Bestaande locatie-detail/sheet |
| Klein kaartgebied | Bestaande `/api/v1/map/viewport` met volledige individuele locatiedetails | Geen andere presentatie of tapsemantiek dan boven |
| Groot gebied / >999 locaties in kleine viewport | Complete `/api/v1/map/overview` op canonieke intern verborgen datarastercellen | Dezelfde presentatie en tapsemantiek; picker pagineert alle onderliggende cellen |

## Technische wijziging

- `OverviewVisualClustering.kt` heeft één **generieke, deterministische schermpixel-clusterengine** voor zowel projecties van volledige `MapLocation`-lijsten als projecties van backend-`OverviewMarker`-samenvattingen.
- Representatiegewichten tellen laadlocaties, geen laadpunten of connectoren. De groen/rood/onbekend-ring telt de beschikbaarheidsstatus van **iedere locatie precies eenmaal**. Een locatie met onbetrouwbare telling (`CountReliability.Ambiguous`) blijft onbekend, ook als de ruwe `available`-telling positief lijkt.
- `MapScreen.renderNativeMarkers` gebruikt exact dezelfde `createAvailabilityClusterBitmap` voor beide bronnen. De aparte legacy-`createClusterBitmap` met `vrij/totaal`-clusterlabels is verwijderd; die labels blijven wel bestaan voor losse markers.
- Beide cluster-tappaden gebruiken `markerTapAction(count,zoom,MAX_CLUSTER_ZOOM)`. Het bestaande paginatiepaneel blijft nodig voor grote groepen op de overviewroute; bij volledige lokale locatiegroepen gebruikt de selectie het bestaande lokale sheet.
- De geconfigureerde **144 pixel** groepeerafstand en het maximum voor clustertap **zoom 18** zijn voor beide databronnen gelijk. Geen aanpassing van de read-only API, NDW-collector, databasetabellen, filters of dataroutering.

## Test- en releasegates

- Kotlin unit-tests voor gelijke groepsindeling tussen lokale en overviewprojecties, eenmalige statustelling per locatie, onbekende betrouwbaarheid, locatieaantallen versus EVSE-totalen, consistente taps en volgorde-onafhankelijke clustering.
- GitHub Actions: Android unit-tests plus debug APK-build. Aanvullende bestaande API/NAS-workflows blijven als regressiebescherming.
- Na een afzonderlijke merge-/releasebeslissing moet op de Samsung worden vergeleken: dezelfde kaartpositie net vóór en na lokale/overviewschakeling, groepstaps bij zoom 12–18, overlappende locaties, filters, favorietsterren en detailsheets. CI alleen bewijst niet hoe de kaart op het toestel aanvoelt.
- **Geen automatische merge of NAS-deployment.** #113 blijft open tot fysieke acceptatie.
