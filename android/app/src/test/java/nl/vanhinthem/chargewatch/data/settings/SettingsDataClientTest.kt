package nl.vanhinthem.chargewatch.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDataClientTest {
    @Test fun parsesDataStatusContract() {
        val status = parseDataStatus(
            """{
              "lastSuccessfulImportAt":"2026-10-29T15:00:00Z",
              "lastObservedPollAt":"2026-10-29T15:20:00Z",
              "ageSeconds":120.0,
              "isStale":false,
              "staleAfterMinutes":15,
              "locationCount":82130,
              "evseCount":207565,
              "connectorCount":210000,
              "tariffCount":1234,
              "tariffsFeedLastCheckedAt":"2026-10-29T15:10:00Z",
              "tariffsFeedIsStale":false
            }"""
        )

        assertEquals(82130L, status.locationCount)
        assertEquals(207565L, status.evseCount)
        assertEquals(210000L, status.connectorCount)
        assertEquals(1234L, status.tariffCount)
        assertEquals(false, status.isStale)
        assertEquals("2026-10-29T15:20:00Z", status.lastObservedPollAt)
    }
}
