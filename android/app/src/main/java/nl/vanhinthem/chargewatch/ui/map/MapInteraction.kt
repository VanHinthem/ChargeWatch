package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.search.Destination
import nl.vanhinthem.chargewatch.data.search.SearchLocation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

internal enum class MarkerTapAction {
    SelectLocation,
    ZoomCluster,
    SelectClusterLocations
}

internal fun markerTapAction(
    locationCount: Int,
    currentZoom: Double,
    maxClusterZoom: Double
): MarkerTapAction {
    require(locationCount > 0)
    return when {
        locationCount == 1 -> MarkerTapAction.SelectLocation
        currentZoom < maxClusterZoom -> MarkerTapAction.ZoomCluster
        else -> MarkerTapAction.SelectClusterLocations
    }
}

/** An active blue pin survives search edits and map navigation; clearing removes it. */
internal fun destinationAfterQueryEdit(query: String, selected: Destination?): Destination? =
    if (query.isBlank()) null else selected

/**
 * Show the return action whenever the map has drifted away from the saved
 * address, regardless of whether that happened by hand or via a charger card.
 *
 * The distance tolerance follows zoom: small drags matter when zoomed in,
 * but a tiny camera offset must not create a flickering button.
 * Also show it after a meaningful manual zoom, because returning restores
 * the fitted address-and-chargers overview rather than a hard-coded zoom.
 */
internal fun shouldShowReturnToDestination(
    destination: Destination?,
    cameraLatitude: Double,
    cameraLongitude: Double,
    cameraZoom: Double,
    destinationZoom: Double = 14.0,
    overview: DestinationOverview? = null
): Boolean {
    if (destination == null || !cameraLatitude.isFinite() ||
        !cameraLongitude.isFinite() || !cameraZoom.isFinite()
    ) return false
    val targetZoom = overview?.zoom ?: destinationZoom
    val targetLatitude = overview?.latitude ?: destination.latitude
    val targetLongitude = overview?.longitude ?: destination.longitude
    if (abs(cameraZoom - targetZoom) >= 0.75) return true

    val latMeters = (cameraLatitude - targetLatitude) * 111_200.0
    val longitudeMeters = (cameraLongitude - targetLongitude) *
        111_200.0 * cos(targetLatitude * PI / 180.0)
    val toleranceMeters = (90.0 * 2.0.pow(targetZoom - cameraZoom))
        .coerceIn(25.0, 500.0)
    return hypot(latMeters, longitudeMeters) >= toleranceMeters
}

/** The actual camera of the overview containing the address and nearby charging locations. */
internal data class DestinationOverview(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double
)

/** Give the expanded search panel room, but never make the fitting area disappear. */
internal fun overviewTopPaddingPixels(mapHeightPx: Int, panelBottomPx: Int, marginPx: Int): Int {
    if (mapHeightPx <= 0) return marginPx.coerceAtLeast(0)
    val margin = marginPx.coerceAtLeast(0)
    return (panelBottomPx + margin).coerceIn(
        margin.coerceAtMost((mapHeightPx * 0.65).toInt()),
        (mapHeightPx * 0.65).toInt().coerceAtLeast(0)
    )
}

/** The address is always the first bounds point; never depend on viewport markers. */
internal fun destinationOverviewCoordinates(
    destination: Destination,
    nearby: List<SearchLocation>
): List<Pair<Double, Double>> = listOf(destination.latitude to destination.longitude) +
    nearby.asSequence().filter {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }.take(5).map { it.latitude to it.longitude }.toList()

/** Nearby suggestions use the same OCPI feature identity as map markers and saved favorites. */
internal fun isNearbyFavorite(item: SearchLocation, favoriteIds: Set<String>): Boolean =
    item.featureId in favoriteIds

internal fun nearestLocationsCountLabel(count: Int): String =
    if (count == 1) "1 dichtstbijzijnde locatie"
    else "${count.coerceAtLeast(0)} dichtstbijzijnde locaties"
