package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.search.Destination
import nl.vanhinthem.chargewatch.data.search.SearchLocation
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
    @Test
    fun fittedOverviewIsReferenceForReturnButtonNotTheBluePin() {
        val overview = DestinationOverview(51.769, 5.531, 12.8)
        // Asymmetric list padding shifts the camera's center away from the blue pin.
        assertFalse(shouldShowReturnToDestination(destination, 51.769, 5.531, 12.8, overview = overview))
        assertTrue(shouldShowReturnToDestination(destination, 51.769, 5.531, 16.0, overview = overview))
        assertTrue(shouldShowReturnToDestination(destination, 51.780, 5.541, 12.8, overview = overview))
        assertFalse(shouldShowReturnToDestination(destination, overview.latitude, overview.longitude,
            overview.zoom, overview = overview))
    }

    @Test
    fun overviewContainsBluePinAndAtMostFiveValidNearestChargers() {
        val chargers = (0..6).map { index ->
            SearchLocation(
                featureId = "charger-$index", address = "", city = "Oss", operatorName = null,
                latitude = 51.765 + index * 0.001, longitude = 5.525,
                availableCount = 0, totalCount = 1, unknownCount = 0
            )
        }
        val points = destinationOverviewCoordinates(destination, chargers)
        assertEquals(6, points.size)
        assertEquals(destination.latitude to destination.longitude, points.first())
        assertEquals(chargers[4].latitude to chargers[4].longitude, points.last())
        assertEquals(listOf(destination.latitude to destination.longitude),
            destinationOverviewCoordinates(destination, emptyList()))
    }

    @Test
    fun overviewPaddingLeavesRoomForMapBelowExpandedPanel() {
        assertEquals(640, overviewTopPaddingPixels(1000, 600, 40))
        assertEquals(650, overviewTopPaddingPixels(1000, 990, 40))
        assertEquals(40, overviewTopPaddingPixels(1000, 0, 40))
        assertEquals(40, overviewTopPaddingPixels(0, 500, 40))
    }

}
