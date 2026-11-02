package nl.vanhinthem.chargewatch.widget

import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetPresentationTest {
    private val now = Instant.parse("2026-11-02T15:00:00Z").toEpochMilli()
    private fun snapshot(free: Int?, total: Int?, reliability: String = "Safe",
                         observed: String? = "2026-11-02T14:59:00Z",
                         sourceStale: Boolean? = false,
                         fetched: Long = now - 30_000) =
        WidgetSnapshot(free, total, reliability, observed, sourceStale, fetched)

    @Test fun validCountWithFreeEvseIsGreen() {
        val result = widgetRow("a", "Thuis", snapshot(2, 4), now - 30_000, now)
        assertEquals("2/4", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        assertFalse(result.isLastKnown)
    }

    @Test fun validZeroAvailableIsRed() {
        val result = widgetRow("a", "Werk", snapshot(0, 3), now - 30_000, now)
        assertEquals("0/3", result.label)
        assertEquals(AvailabilityStatus.NoneAvailable, result.status)
    }

    @Test fun staleFeedRetainsKnownColorButIsMarkedLastKnown() {
        val result = widgetRow("a", "Werk",
            snapshot(1, 2, sourceStale = true), now - 30_000, now)
        assertEquals("1/2", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        assertTrue(result.isLastKnown)
        assertTrue(result.freshness.contains("Laatst bekend"))
    }

    @Test fun ambiguousOrInvalidCountIsQuestionMark() {
        listOf(snapshot(null, null), snapshot(3, 2),
            snapshot(1, 2, reliability = "Ambiguous"), snapshot(0, 0)).forEach {
            val result = widgetRow("a", "Unknown", it, now - 30_000, now)
            assertEquals("?", result.label)
            assertEquals(AvailabilityStatus.Unknown, result.status)
        }
    }

    @Test fun partialRefreshDoesNotMakeOldCacheCurrent() {
        val lastFetch = now - 600_000
        val result = widgetRow("a", "Thuis",
            snapshot(2, 2, fetched = lastFetch), now, now)
        assertEquals("2/2", result.label)
        assertTrue(result.isLastKnown)
    }

    @Test fun missingTimestampCannotImplyFreshFeed() {
        val result = widgetRow("a", "Thuis",
            snapshot(2, 2, observed = null), now - 30_000, now)
        assertTrue(result.isLastKnown)
        assertEquals("2/2", result.label)
    }

    @Test fun responsiveLayoutUsesConservativeRowLimits() {
        assertEquals(2, visibleWidgetRows(150f, 215f))
        assertEquals(3, visibleWidgetRows(285f, 140f))
        assertEquals(5, visibleWidgetRows(285f, 215f))
    }
}
