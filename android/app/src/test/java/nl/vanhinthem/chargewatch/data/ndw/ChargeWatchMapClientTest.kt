package nl.vanhinthem.chargewatch.data.ndw

import org.junit.Assert.*
import org.junit.Test

class ChargeWatchMapClientTest {
    @Test fun parsesViewportIdentityCountsAndCategories() {
        val raw = """{"truncated":false,"feedLastObservedAt":"2026-10-29T15:00:00Z","feedIsStale":false,
          "locations":[{"featureId":"NL-NUO-abc","longitude":5.53,"latitude":51.75,
          "address":"Teststraat 1","operatorName":"Test","lastUpdated":null,
          "totalCount":2,"availableCount":1,"connectorCategories":[
          {"available":1,"total":2,"connectorType":"IEC_62196_T2","powerType":"AC_3_PHASE","powerMax":11000}]}]}"""
        val item = parseChargeWatchMap(raw).single()
        assertEquals("NL-NUO-abc", item.featureId)
        assertEquals("1/2", item.availability.label)
        assertEquals(CountReliability.Safe, item.availability.reliability)
        assertEquals(1, item.connectorCategories.size)
        assertEquals(11000.0, item.connectorCategories.single().powerMax!!, 0.1)
        assertEquals("2026-10-29T15:00:00Z", item.feedLastObservedAt)
        assertEquals(false, item.feedIsStale)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesTruncatedViewport() {
        parseChargeWatchMap("""{"truncated":true,"locations":[]}""")
    }
}
