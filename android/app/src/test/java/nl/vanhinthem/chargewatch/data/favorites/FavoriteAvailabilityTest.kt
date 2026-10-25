package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FavoriteAvailabilityTest {
    private val now = Instant.parse("2026-10-25T11:00:00Z")
    private fun location(updated: String?, reliability: CountReliability = CountReliability.Safe) =
        MapLocation("NL-A", 5.5, 51.7, "Test", updated, AvailabilitySummary(1, 2, reliability))

    @Test fun currentTrustedStatusIsShown() {
        val result = favoriteAvailability(location("2026-10-25T10:55:00Z"), now)
        assertEquals("1/2", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
    }

    @Test fun oldTimestampKeepsKnownRatioButLabelsAge() {
        val result = favoriteAvailability(location("2026-10-25T10:27:00Z"), now)
        assertEquals("1/2", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        org.junit.Assert.assertTrue(result.description.contains("33 min"))
    }

    @Test fun missingTimestampAndUnreliableStatusesAreUnknown() {
        assertEquals("1/2", favoriteAvailability(location(null), now).label)
        assertEquals("?", favoriteAvailability(location("2026-10-25T10:58:00Z", CountReliability.Ambiguous), now).label)
        assertEquals("1/2", favoriteAvailability(location("bad"), now).label)
        assertEquals("?", favoriteAvailability(null, now).label)
    }
}
