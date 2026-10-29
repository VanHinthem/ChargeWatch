package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.search.Destination

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
