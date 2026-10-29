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

    private fun location(
        sourceUpdated: String?,
        observedAt: String? = "2026-10-25T10:58:00Z",
        feedIsStale: Boolean? = false,
        reliability: CountReliability = CountReliability.Safe
    ) = MapLocation(
        featureId = "NL-A",
        longitude = 5.5,
        latitude = 51.7,
        title = "Test",
        lastUpdated = sourceUpdated,
        availability = AvailabilitySummary(1, 2, reliability),
        feedLastObservedAt = observedAt,
        feedIsStale = feedIsStale
    )

    @Test fun recentFeedObservationIsShownInsteadOfSourceObjectAge() {
        val result = favoriteAvailability(
            location("2026-10-20T10:00:00Z"),
            now
        )
        assertEquals("1/2", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        assertEquals("2 min geleden gecontroleerd", result.description)
        assertEquals("2026-10-20T10:00:00Z", result.sourceUpdated)
    }

    @Test fun staleFeedGetsExplicitWarning() {
        val result = favoriteAvailability(
            location(
                sourceUpdated = "2026-10-25T10:59:00Z",
                observedAt = "2026-10-25T10:40:00Z",
                feedIsStale = true
            ),
            now
        )
        assertEquals(
            "Gegevens mogelijk verouderd · laatste controle 20 min geleden",
            result.description
        )
    }

    @Test fun unknownFeedObservationDoesNotInventFreshness() {
        val result = favoriteAvailability(
            location("2026-10-25T10:59:00Z", observedAt = null, feedIsStale = null),
            now
        )
        assertEquals("Laatste controle onbekend", result.description)
    }

    @Test fun unreliableStatusKeepsUnknownAvailabilityButFeedFreshness() {
        val result = favoriteAvailability(
            location(
                sourceUpdated = "2026-10-25T10:58:00Z",
                observedAt = "2026-10-25T11:00:00Z",
                reliability = CountReliability.Ambiguous
            ),
            now
        )
        assertEquals("?", result.label)
        assertEquals(AvailabilityStatus.Unknown, result.status)
        assertEquals("Zojuist gecontroleerd", result.description)
    }

    @Test fun missingLocationIsUnknown() {
        val result = favoriteAvailability(null, now)
        assertEquals("?", result.label)
        assertEquals(AvailabilityStatus.Unknown, result.status)
        assertEquals("Nog niet gecontroleerd", result.description)
    }
}
