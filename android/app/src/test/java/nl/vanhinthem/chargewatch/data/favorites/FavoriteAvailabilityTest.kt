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

    @Test fun currentTrustedStatusShowsFetchFreshness() {
        val result = favoriteAvailability(
            location = location("2026-10-24T01:00:00Z"),
            now = now,
            checkedAt = now.minusSeconds(120)
        )
        assertEquals("1/2", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        assertEquals("2 min geleden gecontroleerd", result.description)
    }

    @Test fun veryOldSourceTimestampDoesNotMakeRecentlyFetchedStatusLookStale() {
        val result = favoriteAvailability(
            location = location("2026-10-20T10:00:00Z"),
            now = now,
            checkedAt = now
        )
        assertEquals("Zojuist gecontroleerd", result.description)
        assertEquals("2026-10-20T10:00:00Z", result.sourceUpdated)
    }

    @Test fun missingCheckTimeIsExplicit() {
        val result = favoriteAvailability(location("2026-10-25T10:58:00Z"), now)
        assertEquals("Nog niet gecontroleerd", result.description)
    }

    @Test fun unreliableStatusKeepsUnknownAvailabilityButShowsCheckAge() {
        val result = favoriteAvailability(
            location = location("2026-10-25T10:58:00Z", CountReliability.Ambiguous),
            now = now,
            checkedAt = now.minusSeconds(60)
        )
        assertEquals("?", result.label)
        assertEquals(AvailabilityStatus.Unknown, result.status)
        assertEquals("1 min geleden gecontroleerd", result.description)
    }

    @Test fun missingLocationIsUnknown() {
        val result = favoriteAvailability(null, now, now)
        assertEquals("?", result.label)
        assertEquals(AvailabilityStatus.Unknown, result.status)
        assertEquals("Nog niet gecontroleerd", result.description)
    }
}
