package nl.vanhinthem.chargewatch.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSearchClientTest {
    @Test fun parsesLocationSearchIdentityAndCounts() {
        val results = parseSearchLocations(
            """[{
              "id":42,
              "countryCode":"NL",
              "partyId":"VAN",
              "sourceId":"abc",
              "address":"Schietspoel 18",
              "city":"Oss",
              "operatorName":"Vattenfall InCharge",
              "latitude":51.765,
              "longitude":5.525,
              "evseCount":2,
              "availableCount":1
            }]"""
        )

        val result = results.single()
        assertEquals("NL-VAN-abc", result.featureId)
        assertEquals("Schietspoel 18", result.address)
        assertEquals("Oss", result.city)
        assertEquals(1, result.availableCount)
        assertEquals(2, result.totalCount)
    }
}
