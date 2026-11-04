# ChargeWatch #113 — kaartclustering herstellen na Android-acceptatietest

**Datum:** 4 november 2026.  
**Branch:** `fix/map-overview-113-street-level-clustering`.  
**Status:** implementatie en geautomatiseerde tests; **niet gemerged of gedeployed**.

## Probleem (gezien op Samsung)

Fase D toont op kaartniveau rond Oss regelmatig verdeelde backendrastercellen met aantallen 19, 17, 10, enz. Na tikken op 19 blijft er een regelmatige verzameling kleine rasterclusters zichtbaar. De oude ChargeWatch-kaart toont op dezelfde schaal veel vaker individuele laadlocaties met `vrij/totaal` en lokale, nabijheidsafhankelijke clustering. Dit is onwenselijk: een canoniek Web Mercator-raster is voor **opslag en volledigheidscontrole**, niet voor de zichtbare kaartlayout.

## Productgedrag vanaf deze correctie

1. **Landelijke en grote regionale kaart:** blijft volledige, door de backend gefilterde overview gebruiken. De backend berekent per volledige rastercel naast de bestaande statuspartitionering ook de *gemiddelde longitude/latitude van de echte laadlocaties*. De marker komt niet meer op het geometrische celmidden. Android projecteert alle maximaal 500 backendmarkers naar schermcoördinaten en groepeert nabijgelegen cellen met dezelfde `CLUSTER_DISTANCE_PX=144`-strategie als de oude kaart. Het zichtbare cluster staat op het locatiegewogen gemiddelde van de leden. Een getoond aantal is altijd de som van alle vertegenwoordigde laadlocaties, de groen/rood/onbekend-ring gebruikt de exact opgetelde locatiestatussen. Elke backendmarker is lid van precies één zichtbaar cluster; er verdwijnen geen sites.
2. **Stad/straatniveau:** als de zichtbare bbox ten hoogste 1 graad² beslaat, vraagt de overview-kaart de volledige bestaande `/api/v1/map/viewport`-response op, inclusief adres, operator, connectors en `vrij/totaal`. Zo hergebruiken we **exact dezelfde individuele markers en scherm-clustering als de oude kaart**. `MapMode.Overview` blijft actief; de routekeuze is transparant.
3. **Hoge dichtheid:** als de volledige lokale viewport wegens het 999-limietcontract met HTTP 413 wordt geweigerd, schakelt Android uitsluitend in dat geval over naar de *complete* backend-overview (geen afgekapt of misleidend kaartbeeld). Overige API-fouten worden gemeld, niet verzwegen. Bij >1 graad² direct de landelijke overview, onafhankelijk van zoom-label.
4. **Interactie:** tikken op een zichtbaar aggregaat zoomt naar het locatiegewogen middelpunt en circa twee zoomstappen, niet naar een kunstmatige vierkante rastercel. Alleen bij zeer hoge zoom met nog één onderliggende servercel komt de bestaande gepagineerde celkeuze in beeld. Bij volledige individuele locaties blijven favorieten en de oude selectie-interacties ongewijzigd.
5. **Backendcompatibiliteit:** responsecontract v1, `kind`, `gridLevel`, `gridX`, `gridY`, bron-`featureId`, filtersemantiek, vaste coverage, maximaal 500 backendmarkers en de read-only status blijven ongewijzigd. Alleen de locatie van een cluster is voortaan het werkelijke locatiegewogen gemiddelde, niet het rastercentrum. De onafhankelijk berekende PostGIS-HTTP-oracle toetst deze nieuwe positie.

## Validatie

- Android unit: regionale routekeuze, de exacte 999-overflowfallback, exacte status- en locatietellingen bij samenvoegen, gewogen clusterposities, volgorde-onafhankelijke grouping en behoud van afzonderlijke servercellen bij hoge zoom.
- API/isolated PostGIS HTTP: alle eerdere coverage/filter/status/-pagineringstests, met nieuw verwachtingcontract voor locatiegewogen clustercentra.
- CI: Android unit/build-debug, read-only API, NAS Image build/safety en aanvullende CI conform PR. Geen NAS-redeploy.
- **Voor acceptatie op de fysieke telefoon:** screenshots bij heel Nederland, omgeving Oss, na 1–3 clustertaps en op straatniveau naast de oude kaart; testen van filters en favorite-locaties; beoordelen van framerate/latency. Een geslaagde CI betekent niet dat de UX al is goedgekeurd.

## Releasegrens

Wijziging eerst apart in draft-PR. **Geen merge, GHCR-hoofdrelease, NAS-deployment, Android-installatie of featureflagwijziging zonder afzonderlijk gebruikersakkoord.** De reeds draaiende productieversie blijft ongewijzigd tot de review.
