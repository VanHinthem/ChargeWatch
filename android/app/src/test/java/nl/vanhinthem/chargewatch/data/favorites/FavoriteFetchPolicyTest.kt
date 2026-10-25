package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FavoriteFetchPolicyTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")
    private fun favorite(id: String) = FavoriteLocation(id, id, 51.7, 5.5)
    private fun location(id: String) = MapLocation(
        id, 5.5, 51.7, id,
        "2026-10-25T09:00:00Z",
        AvailabilitySummary(1, 2, CountReliability.Safe)
    )

    @Test fun recentMapObservationSkipsNetworkDespiteOldSourceTimestamp() {
        assertEquals(
            emptyList<FavoriteLocation>(),
            favoritesNeedingFetch(
                listOf(favorite("A")), mapOf("A" to location("A")),
                now.minusSeconds(60), emptyMap(), null, now
            )
        )
    }

    @Test fun onlyMissingAndOlderFetchedFavoritesAreRequested() {
        val favorites = listOf(favorite("A"), favorite("B"))
        assertEquals(
            listOf(favorite("B")),
            favoritesNeedingFetch(
                favorites, mapOf("A" to location("A")), now.minusSeconds(60),
                emptyMap(), null, now
            )
        )
        assertEquals(
            favorites,
            favoritesNeedingFetch(
                favorites, mapOf("A" to location("A")), now.minusSeconds(181),
                emptyMap(), null, now
            )
        )
    }

    @Test fun recentFavoriteRequestSkipsNetworkWithoutMapLocation() {
        assertEquals(
            emptyList<FavoriteLocation>(),
            favoritesNeedingFetch(
                listOf(favorite("B")), emptyMap(), null,
                mapOf("B" to location("B")), now.minusSeconds(30), now
            )
        )
    }
}
