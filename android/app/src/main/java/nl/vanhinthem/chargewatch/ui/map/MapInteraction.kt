package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.search.Destination
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
 * the original address view (zoom 14).
 */
internal fun shouldShowReturnToDestination(
    destination: Destination?,
    cameraLatitude: Double,
    cameraLongitude: Double,
    cameraZoom: Double,
    destinationZoom: Double = 14.0
): Boolean {
    if (destination == null || !cameraLatitude.isFinite() ||
        !cameraLongitude.isFinite() || !cameraZoom.isFinite()
    ) return false
    if (abs(cameraZoom - destinationZoom) >= 0.75) return true

    val latMeters = (cameraLatitude - destination.latitude) * 111_200.0
    val longitudeMeters = (cameraLongitude - destination.longitude) *
        111_200.0 * cos(destination.latitude * PI / 180.0)
    val toleranceMeters = (90.0 * 2.0.pow(destinationZoom - cameraZoom))
        .coerceIn(25.0, 500.0)
    return hypot(latMeters, longitudeMeters) >= toleranceMeters
}
