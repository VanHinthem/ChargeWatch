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

    @Test fun parsesNearestDistanceAndStaleReliability() {
        val item = parseSearchLocations(
            """[{"countryCode":"NL","partyId":"VAN","sourceId":"near",
                "address":"Adres","city":"Oss","latitude":51.765,"longitude":5.525,
                "evseCount":2,"availableCount":1,"unknownCount":1,
                "feedIsStale":true,"distanceMeters":375.4}]"""
        ).single()
        assertEquals(375.4, item.distanceMeters!!, 0.001)
        assertEquals(true, item.feedIsStale)
        assertEquals(1, item.unknownCount)
    }

    @Test fun missingUnknownCountMustNotImplyZeroUnknown() {
        val item = parseSearchLocations(
            """[{"countryCode":"NL","partyId":"VAN","sourceId":"missing",
                "latitude":51.765,"longitude":5.525,"evseCount":2,"availableCount":2}]"""
        ).single()
        assertEquals(Int.MAX_VALUE, item.unknownCount)
    }
}
