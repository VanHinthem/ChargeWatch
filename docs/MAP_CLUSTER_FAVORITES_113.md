# ChargeWatch #113 — favorietster op clusters

**Status:** Android-fix in review, featurebranch `fix/map-cluster-favorite-badge-113`. Geen API-, database- of NAS-wijzigingen.

## Gedrag

- **Individuele laadlocatie:** bestaande favorietster blijft zichtbaar als de `featureId` lokaal is opgeslagen.
- **Lokaal cluster:** toont dezelfde favorietster zodra minimaal één `MapLocation.featureId` overeenkomt met een lokaal opgeslagen favoriet. Dit is exact, ook met kaartfilters.
- **Landelijk cluster zonder actieve filters:** toont een ster wanneer een opgeslagen favoriet zich in één van de door de zichtbare cluster vertegenwoordigde *canonieke Web Mercator-rastercellen* bevindt. Favorieten hebben in de lokale DataStore zowel een string-`featureId` als lat/lon. Er worden nooit favoriete ID's, aliassen of locaties naar een externe API verzonden.
- **Landelijk cluster met actieve filters:** een geaggregeerde rastercel bevat geen individuele ID's; cel-lidmaatschap alleen bewijst niet of de favoriet de filters doorstaat. Om onjuiste sterren bij uitgesloten favorieten te voorkomen, tonen we alleen een ster wanneer een teruggegeven individuele `OverviewMarker.Location.featureId` exact matcht. In dit specifieke geval kan een gefilterd aggregaat dus een favoriet bevatten zonder dat de ster verschijnt. Dit is een bewuste privacy-/correctheidsbeperking en geen claim van volledige filterpariteit voor favoriete aggregaten.
- **Visuele stijl:** hetzelfde bestaande goudgele sterbadge rechtsboven op alle clusterringen; het aantal locaties en de beschikbaarheidsring veranderen niet. De bestaande marker-icon-cache key bevat de favorietstatus, zodat toggles direct zichtbaar worden.
- Favorieten worden lokaal opgeslagen en blijven onaangeroerd. De overviewbron, filters en status-/countcontracten blijven ongewijzigd.

## Validatie

Regressietests dekken local feature-ID-membership, canonical Oss-celcoördinaten, gegroepeerde meerdere backendcellen, afwezigheid van ster buiten de cel, ongeldige coördinaten, exacte singleton-identiteit bij filters en conservatief gedrag op gefilterde geaggregeerde cellen.

CI: Android `testDebugUnitTest` en `assembleDebug`; daarna acceptatie op Samsung S23 Ultra op lokale kaart en landelijke overzichtsmodus, ster voor favoriete clusters, verwijdering en herberekening bij zoomen. **Niet mergen of deployen zonder afzonderlijk akkoord.**
