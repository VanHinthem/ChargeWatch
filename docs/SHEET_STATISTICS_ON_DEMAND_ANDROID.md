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


## Aanvulling 5 november — aansluiting op batch-API PR #124

De eerder beschreven bestaande geografische `FavoritesAvailabilityClient` is vanaf deze Android-PR niet langer de actieve dataroute van het favorietenscherm; zie [API PR #124](https://github.com/VanHinthem/ChargeWatch/pull/124). De bestaande helper blijft voorlopig in de code beschikbaar, maar de app gebruikt een nieuwe `FavoriteBatchClient`.

- Automatisch bij het openen van tabblad Favorieten: haal alle lokaal opgeslagen unieke favoriet-ID's via `POST /api/v1/locations/by-ndw-id/batch` op, in groepen van maximaal **20 per request**. Nieuwe favorieten worden atomair begrensd op 20 opgeslagen items; de gebruiker krijgt een melding bij de limiet. Bestaande gebruikers met >20 opgeslagen ID's verliezen geen favorieten en kunnen altijd items verwijderen.
- Vraag de volledige detailstructuur, EVSEs, connectoren, tariefevaluatie en statistieken op met `includeStatistics=true`.
- De 2-minuten succesvolle-fetch-cache blijft bestaan. Alleen als de cache verlopen is, er nieuwe ID's zijn, of de gebruiker handmatig op verversen tikt, zijn extra requests nodig. Een ontbrekend ID wordt tijdelijk als verwerkt gemarkeerd en als waarschuwing getoond, zonder de overige favorieten te blokkeren.
- Wanneer de gebruiker een favoriet opent, krijgt de volledige `LocationSelectionSheet` de **al geladen** `FavoriteBatchLocation` als preloaded detail/tarieven/geschiedenis; er volgen geen redundante losse GET-verzoeken. Bij ontbrekende cache wordt uitsluitend dat ene ID via dezelfde batchendpoint opgehaald.
- De compacte kaart-sheet werkt nog steeds met individuele details en haalt historie **pas bij uitklappen**. Er is geen nieuwe kaart-API nodig.
- De nieuwe batchclient is los van de UI en kan later vanuit Android-widget-werk hergebruikt worden. De widget zelf wordt in deze PR nog niet gebouwd.
- Falen van de batch-API wordt als fout zichtbaar; de app valt **niet stilzwijgend** terug op geografische viewport-approximaties. Dit voorkomt schijnvolledigheid en maakt de volgorde van uitrollen duidelijk: **eerst PR #124 mergen en NAS API deployen, dan PR #123 naar Android release**.

Risico/acceptatie: correcte statuscounts en historische tijdreeksen, prijzen in volledige sheet, automatische refresh bij tab openen, max twintig per request, cache na terugkeer binnen twee minuten en de verversknop; voor het eerste Android-testmoment moet de nieuwe batch-API op de test-NAS beschikbaar zijn.
