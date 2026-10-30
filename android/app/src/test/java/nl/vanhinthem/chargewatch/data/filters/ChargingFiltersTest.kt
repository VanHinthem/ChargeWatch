package nl.vanhinthem.chargewatch.data.filters

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ChargingFiltersTest {
    @Test fun quickFiltersAreReversibleAndPowerBandsExclusive() {
        val initial = ChargingFilters()
        val active = initial.toggleQuick("free")
            .toggleQuick("lt50").toggleQuick("gte150")
        assertEquals(2, active.activeCount)
        assertEquals("gte150", active.power)
        assertTrue(active.active)
        // CCS2 remains available in the full filtersheet, not a quick action.
        assertEquals(initial, initial.toggleQuick("ccs2"))
        assertFalse(active.toggleQuick("free").minFree > 0)
        assertNull(active.toggleQuick("gte150").power)
    }

    @Test fun repeatedQueryParametersPreserveOperatorLabels() {
        val f = ChargingFilters(
            connectorTypes = setOf("ccs2", "chademo"),
            modes = setOf("dc"),
            power = "gte150", minFree = 2,
            operators = setOf("Vattenfall InCharge", "Fastned")
        )
        val url = f.appendTo("https://example.nl/api/v1/locations".toHttpUrl().newBuilder()).build()
        assertEquals(listOf("ccs2", "chademo"), url.queryParameterValues("connectorType"))
        assertEquals(listOf("Fastned", "Vattenfall InCharge"), url.queryParameterValues("operator"))
        assertEquals("gte150", url.queryParameter("power"))
        assertEquals("2", url.queryParameter("minFree"))
    }

    @Test fun liveSelectionAndResetKeepAllGroupsConsistent() {
        // Mirrors the immutable filter values delivered directly by each sheet choice.
        val selected = ChargingFilters()
            .copy(connectorTypes = setOf("type2", "ccs2"))
            .copy(power = "gte150")
            .copy(minFree = 2)
            .copy(operators = setOf("Fastned"))
        assertEquals(4, selected.activeCount)
        assertEquals(setOf("type2", "ccs2"), selected.connectorTypes)
        assertEquals("gte150", selected.power)

        // Selecting a different power replaces the band; other groups remain set.
        val changed = selected.copy(power = "lt50")
        assertEquals(4, changed.activeCount)
        assertEquals("lt50", changed.power)
        assertEquals(selected.operators, changed.operators)

        // Header reset sends a new empty value, just like turning quick chips off.
        val reset = ChargingFilters()
        assertFalse(reset.active)
        assertEquals(0, reset.activeCount)
        assertTrue(reset.connectorTypes.isEmpty())
        assertNull(reset.power)
        assertEquals(0, reset.minFree)
        assertTrue(reset.operators.isEmpty())
    }

    @Test fun legacyServerCannotSilentlyIgnoreActiveFilters() {
        assertTrue(supportsFiltering(false, null))
        assertFalse(supportsFiltering(true, null))
        assertFalse(supportsFiltering(true, "0"))
        assertTrue(supportsFiltering(true, "1"))
    }
}
