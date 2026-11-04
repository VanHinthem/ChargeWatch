# ChargeWatch — historische statistieken pas laden bij uitgebreide sheet

**Datum:** 4 november 2026  
**Status:** implementatie op featurebranch `perf/android-sheet-lazy-statistics`; niet gemerged of gedeployed.

## Huidige datastroom (voor deze wijziging)

- Kaart: `LocationSelectionSheet` start als compacte sheet. `LocationEnrichmentClient.load(featureId)` deed onmiddellijk twee GET-requests: de locatiespecificatie via `/api/v1/locations/by-ndw-id/{featureId}` en statistieken via `.../statistics`. De compacte kaart-sheet had geen statistieken nodig.
- Favorietenlijst: lokale `FavoritesRepository` DataStore slaat eigen naam/alias, ID en coördinaten op. `FavoritesAvailabilityClient.refresh` vraagt beschikbaarheidsinformatie op via de **bestaande viewport-API** (ChargeWatch met NDW fallback), combineert dichtbij gelegen favoriete locaties in één viewport en vraagt ver uiteen gelegen groepen parallel op. `favoritesNeedingFetch` gebruikt twee minuten succesvolle-fetch-cache. De favorietenlijst vraagt geen statistieken en is geen by-ID-bulkclient.
- Tik op favoriet: hergebruik kaart-/favorietcache indien beschikbaar, anders een kleine viewport-request; daarna `LocationSelectionSheet(initiallyExpanded=true,compactAllowed=false)`. Deze opent direct volledig en had meteen beide locatie- en statistiek-GETs nodig.

## Nieuw gedrag

1. `LocationEnrichmentClient.load(featureId, mapLocation)` vraagt **alleen** basisdetails en tarieven op. Deze response blijft gedeeld tussen compacte en volledige sheet; ook de nieuwe landelijke kaart voorziet zijn individuele marker zo van volledige data.
2. De afzonderlijke `loadStatistics(featureId)` haalt uitsluitend de experimentele uurstatistieken op. Bij onbereikbaarheid is het resultaat `null`; een lege geldige reeks is een geldige response zonder uurdekking.
3. De sheet voert de statistiekrequest pas uit als `SheetValue.Expanded` actief is. Resultaten en foutstatus worden per geselecteerde `featureId` binnen dezelfde sheet onthouden. Compact→expanded→compact→expanded veroorzaakt dus hoogstens één extra GET, niet steeds opnieuw.
4. Favorieten openen al met `initiallyExpanded=true` en halen daarom de historie **direct** op. De bestaande beschikbaarheidsverversing in Favorieten en het scherm voor favorieten blijven functioneel ongewijzigd.
5. De historie blijft gescheiden van tariefdetails: een tijdelijke statistiekfout maakt de detail- en tariefinformatie niet ongeldig; de uitgebreide sheet toont een gerichte melding.

## Tests en release

- Unit-test met in-memory OkHttp-interceptor bevestigt: de detail-GET doet géén statistiek-GET; een aparte `loadStatistics` doet precies één statistiek-GET; een mislukte statistiekresponse laat detailgegevens bruikbaar; een ongeldige ID verzendt geen HTTP-verzoek.
- Pure sheetbeleidtests bevestigen: compact vraagt geen historie; opengeklapte sheet vraagt die wel; geldige lege historie en een mislukte poging worden niet telkens opnieuw opgehaald; favoriet opent direct in uitgebreide modus.
- Geen .NET-, database-, filter- of collectorwijziging. Geen NAS-deployment vereist. Een nieuw gebouwde Android-APK volstaat na merge; fysieke acceptatie op Samsung blijft nodig.
