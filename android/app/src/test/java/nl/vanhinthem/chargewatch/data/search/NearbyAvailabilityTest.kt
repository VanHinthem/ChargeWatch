package nl.vanhinthem.chargewatch.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyAvailabilityTest {
    private fun charger(
        free: Int = 2,
        total: Int = 2,
        unknown: Int = 0,
        feedStale: Boolean? = false,
        sourceStale: Boolean? = false
    ) = SearchLocation(
        featureId = "NL-VAN-abc", address = "Schietspoel 18", city = "Oss",
        operatorName = null, latitude = 51.765, longitude = 5.525,
        availableCount = free, totalCount = total, unknownCount = unknown,
        feedIsStale = feedStale, isStale = sourceStale
    )

    @Test fun oldSourceUpdateDoesNotTurnKnownCountsIntoQuestionMark() {
        // Regression #99: map shows 2/2 while alternatives mistakenly show '?'.
        val result = nearbyAvailabilityDisplay(charger(sourceStale = true))
        assertEquals("2/2", result.label)
        assertEquals(NearbyAvailabilityTone.Available, result.tone)
    }

    @Test fun APIresponseWithOldSourceAndFreshFeedStillShowsCount() {
        val item = parseSearchLocations(
            """[{"countryCode":"NL","partyId":"EFL","sourceId":"alternative",
                "latitude":51.765,"longitude":5.525,
                "evseCount":2,"availableCount":2,"unknownCount":0,
                "isStale":true,"feedIsStale":false}]"""
        ).single()
        assertEquals("2/2", nearbyAvailabilityDisplay(item).label)
        assertEquals(NearbyAvailabilityTone.OlderSource, nearbyAvailabilityDisplay(item).tone)
    }

    @Test fun oldSourceUpdateStillShowsRedWhenNoEvseIsFree() {
        val result = nearbyAvailabilityDisplay(charger(free = 0, total = 2, sourceStale = true))
        assertEquals("0/2", result.label)
        assertEquals(NearbyAvailabilityTone.NoneAvailable, result.tone)
    }

    @Test fun freshKnownCountsKeepAvailabilityColors() {
        val available = nearbyAvailabilityDisplay(charger(free = 3, total = 4))
        assertEquals("3/4", available.label)
        assertEquals(NearbyAvailabilityTone.Available, available.tone)

        val full = nearbyAvailabilityDisplay(charger(free = 0, total = 2))
        assertEquals("0/2", full.label)
        assertEquals(NearbyAvailabilityTone.NoneAvailable, full.tone)
    }

    @Test fun staleOrUnverifiedFeedStillHidesAvailability() {
        for (state in listOf(true, null)) {
            val result = nearbyAvailabilityDisplay(charger(feedStale = state))
            assertEquals("?", result.label)
            assertEquals(NearbyAvailabilityTone.Unknown, result.tone)
        }
    }

    @Test fun unknownAndInconsistentCountsAreNotPresentedAsVerified() {
        val cases = listOf(
            charger(unknown = 1),
            charger(free = 3, total = 2),
            charger(free = -1),
            charger(total = 0)
        )
        for (item in cases) {
            assertEquals("?", nearbyAvailabilityDisplay(item).label)
        }
    }

    @Test fun missingSourceTimestampFlagDoesNotHideKnownCounts() {
        val result = nearbyAvailabilityDisplay(charger(sourceStale = null))
        assertEquals("2/2", result.label)
    }
}
