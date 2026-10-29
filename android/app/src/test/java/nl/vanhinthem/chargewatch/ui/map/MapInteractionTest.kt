package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.search.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private val destination = Destination("Schietspoel 18, Oss", 51.765, 5.525, "adres")

    @Test
    fun returnButtonIsHiddenWithoutDestinationOrAtDestination() {
        assertFalse(shouldShowReturnToDestination(null, 51.9, 5.8, 14.0))
        assertFalse(shouldShowReturnToDestination(destination, 51.765, 5.525, 14.0))
    }

    @Test
    fun smallCameraJitterDoesNotShowReturnButton() {
        assertFalse(shouldShowReturnToDestination(destination, 51.7651, 5.5251, 14.0))
    }

    @Test
    fun manuallyPanningAwayShowsReturnButton() {
        assertTrue(shouldShowReturnToDestination(destination, 51.771, 5.525, 14.0))
        // At higher zoom, even a shorter pan is meaningful.
        assertTrue(shouldShowReturnToDestination(destination, 51.7655, 5.525, 17.0))
    }

    @Test
    fun selectingAnotherChargerShowsReturnButtonWithoutRememberingHowCameraMoved() {
        assertTrue(shouldShowReturnToDestination(destination, 51.785, 5.535, 17.0))
        // Returning to the saved destination's original view clears it.
        assertFalse(shouldShowReturnToDestination(destination, destination.latitude, destination.longitude, 14.0))
    }

    @Test
    fun manualZoomAlsoOffersReturnToOriginalAddressView() {
        assertTrue(shouldShowReturnToDestination(destination, 51.765, 5.525, 16.0))
        assertFalse(shouldShowReturnToDestination(destination, 51.765, 5.525, 14.2))
    }

    @Test
    fun invalidMapPositionsDoNotShowReturnButton() {
        assertFalse(shouldShowReturnToDestination(destination, Double.NaN, 5.525, 14.0))
        assertFalse(shouldShowReturnToDestination(destination, 51.765, 5.525, Double.NaN))
    }
}
