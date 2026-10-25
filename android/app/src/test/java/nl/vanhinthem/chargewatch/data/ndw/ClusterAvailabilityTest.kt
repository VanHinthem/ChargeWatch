package nl.vanhinthem.chargewatch.data.ndw

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterAvailabilityTest {
    private fun location(available: Int?, total: Int?, reliability: CountReliability): MapLocation =
        MapLocation("NL-NUO-test", 5.5, 51.7, "Test", null,
            AvailabilitySummary(available, total, reliability))

    @Test
    fun combinesMultipleLocationEvses() {
        val result = summarizeCluster(listOf(
            location(2, 4, CountReliability.Estimated),
            location(1, 2, CountReliability.Safe)
        ))
        assertEquals("3/6", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
    }

    @Test
    fun zeroFreeIsRed() {
        val result = summarizeCluster(listOf(
            location(0, 4, CountReliability.Estimated),
            location(0, 2, CountReliability.Safe)
        ))
        assertEquals("0/6", result.label)
        assertEquals(AvailabilityStatus.NoneAvailable, result.status)
    }

    @Test
    fun unknownCountFallsBackToNumberOfLocations() {
        val result = summarizeCluster(listOf(
            location(1, 2, CountReliability.Safe),
            location(null, null, CountReliability.Ambiguous)
        ))
        assertEquals("?", result.label)
        assertEquals(AvailabilityStatus.Unknown, result.status)
    }

    @Test
    fun invalidOrOverflowingCapacityDoesNotProducePartialTotals() {
        val invalid = summarizeCluster(listOf(
            location(1, 2, CountReliability.Safe),
            location(3, 2, CountReliability.Safe)
        ))
        assertEquals("?", invalid.label)
        val overflow = summarizeCluster(listOf(
            location(Int.MAX_VALUE, Int.MAX_VALUE, CountReliability.Safe),
            location(1, 1, CountReliability.Safe)
        ))
        assertEquals("?", overflow.label)
        assertEquals(AvailabilityStatus.Unknown, overflow.status)
    }
}
