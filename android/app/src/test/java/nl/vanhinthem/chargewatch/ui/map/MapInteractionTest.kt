package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.search.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    @Test
    fun typingAnotherAddressKeepsExistingDestinationPin() {
        val chosen = Destination("Schietspoel 18 Oss", 51.765, 5.525, "adres")
        assertSame(chosen, destinationAfterQueryEdit("Stationsstraat", chosen))
        assertSame(chosen, destinationAfterQueryEdit("Schietspoel 1", chosen))
    }

    @Test
    fun clearingSearchRemovesDestinationPin() {
        val chosen = Destination("Schietspoel 18 Oss", 51.765, 5.525, "adres")
        assertNull(destinationAfterQueryEdit("", chosen))
        assertNull(destinationAfterQueryEdit("   ", chosen))
    }
}
