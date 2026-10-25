package nl.vanhinthem.chargewatch.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapInteractionTest {
    @Test
    fun individualMarkerSelectsLocation() {
        assertEquals(
            MarkerTapAction.SelectLocation,
            markerTapAction(locationCount = 1, currentZoom = 12.0, maxClusterZoom = 18.0)
        )
    }

    @Test
    fun clusterZoomsBeforeMaximumZoom() {
        assertEquals(
            MarkerTapAction.ZoomCluster,
            markerTapAction(locationCount = 3, currentZoom = 16.0, maxClusterZoom = 18.0)
        )
    }

    @Test
    fun clusterExposesLocationsAtMaximumZoom() {
        assertEquals(
            MarkerTapAction.SelectClusterLocations,
            markerTapAction(locationCount = 3, currentZoom = 18.0, maxClusterZoom = 18.0)
        )
    }
}
