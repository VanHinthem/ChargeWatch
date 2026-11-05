package nl.vanhinthem.chargewatch.data.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesAvailabilityClientTest {
    private fun favorite(id: String, lat: Double, lon: Double) =
        FavoriteLocation(id, id, lat, lon)

    @Test fun nearbyFavoritesShareOneViewport() {
        val viewports = favoriteViewports(
            listOf(
                favorite("A", 51.765, 5.518),
                favorite("B", 51.770, 5.525)
            )
        )

        assertEquals(1, viewports.size)
        assertTrue(viewports.single().areaSquareDegrees <= 0.05)
    }

    @Test fun distantFavoritesUseSeparateViewports() {
        val viewports = favoriteViewports(
            listOf(
                favorite("A", 51.765, 5.518),
                favorite("B", 52.370, 4.895)
            )
        )

        assertEquals(2, viewports.size)
        viewports.forEach { viewport ->
            assertTrue(viewport.areaSquareDegrees < 0.001)
        }
    }

    @Test fun emptyFavoritesNeedNoViewport() {
        assertTrue(favoriteViewports(emptyList()).isEmpty())
    }
}
