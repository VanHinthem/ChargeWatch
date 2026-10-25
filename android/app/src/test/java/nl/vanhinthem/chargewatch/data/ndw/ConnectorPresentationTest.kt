package nl.vanhinthem.chargewatch.data.ndw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorPresentationTest {
    @Test fun wattsAreConvertedToReadableKilowatts() {
        assertEquals("11 kW", formatChargingPower(11040.0))
        assertEquals("22 kW", formatChargingPower(22000.0))
        assertEquals("11,5 kW", formatChargingPower(11500.0))
        assertNull(formatChargingPower(null))
        assertNull(formatChargingPower(-1.0))
    }
    @Test fun availabilitySummaryDoesNotMisrepresentEstimatedOrUnknownCounts() {
        assertEquals("1 van 2 laadpunten vrij",
            availabilityDescription(AvailabilitySummary(1, 2, CountReliability.Safe)))
        assertEquals("0 van 2 laadpunten vrij",
            availabilityDescription(AvailabilitySummary(0, 2, CountReliability.Safe)))
        assertEquals("Naar schatting 1 van 2 vrij",
            availabilityDescription(AvailabilitySummary(1, 2, CountReliability.Estimated)))
        assertEquals("Beschikbaarheid onbekend",
            availabilityDescription(AvailabilitySummary(1, 2, CountReliability.Ambiguous)))
    }
    @Test fun connectorNamesAreHumanReadable() {
        assertEquals("Type 2", connectorDisplayName("IEC_62196_T2"))
        assertEquals("CCS", connectorDisplayName("IEC_62196_T2_COMBO"))
        assertEquals("CHAdeMO", connectorDisplayName("CHADEMO"))
        assertEquals("Overige aansluiting", connectorDisplayName("UNKNOWN_CONNECTOR"))
    }
}
