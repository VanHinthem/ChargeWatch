package nl.vanhinthem.chargewatch.ui.map

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
