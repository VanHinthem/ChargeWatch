package nl.vanhinthem.chargewatch.data.ndw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NdwGeoJsonParserTest {
    private val parser = NdwGeoJsonParser()

    @Test
    fun singleAvailabilityProducesSafeRatio() {
        val location = parser.parse(featureCollection(
            """[
                {"available":1,"total":2,"connector_type":"IEC_62196_T2"}
            ]"""
        )).single()

        assertEquals("1/2", location.availability.label)
        assertEquals(CountReliability.Safe, location.availability.reliability)
        assertEquals(AvailabilityStatus.Available, location.availability.status)
    }

    @Test
    fun repeatedIdenticalCategoryCountsAreNotSummed() {
        val location = parser.parse(featureCollection(
            """[
                {"available":2,"total":4,"connector_type":"IEC_62196_T2"},
                {"available":2,"total":4,"connector_type":"IEC_62196_T2_COMBO"}
            ]"""
        )).single()

        assertEquals(2, location.availability.available)
        assertEquals(4, location.availability.total)
        assertEquals(CountReliability.Safe, location.availability.reliability)
    }

    @Test
    fun differingConnectorCategoryCountsBecomeUnknownInsteadOfDoubleCounted() {
        val location = parser.parse(featureCollection(
            """[
                {"available":1,"total":2,"connector_type":"IEC_62196_T2"},
                {"available":1,"total":1,"connector_type":"IEC_62196_T2_COMBO"}
            ]"""
        )).single()

        assertEquals("?", location.availability.label)
        assertEquals(CountReliability.Ambiguous, location.availability.reliability)
        assertEquals(AvailabilityStatus.Unknown, location.availability.status)
        assertNull(location.availability.available)
        assertNull(location.availability.total)
    }

    @Test
    fun zeroAvailableIsRedSemanticButNotCalledCharging() {
        val location = parser.parse(featureCollection(
            """[{"available":0,"total":1}]"""
        )).single()

        assertEquals(AvailabilityStatus.NoneAvailable, location.availability.status)
        assertEquals("0/1", location.availability.label)
    }

    @Test
    fun thousandFeaturesIsRejectedAsPotentiallyTruncated() {
        val features = (1..1000).joinToString(",") { index ->
            """{"type":"Feature","id":"NL-TST-$index","geometry":{"type":"Point","coordinates":[5.5,51.7]},"properties":{"availabilities":[{"available":1,"total":1}]}}"""
        }

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("""{"type":"FeatureCollection","features":[$features]}""")
        }
    }

    private fun featureCollection(availabilities: String): String =
        """{
          "type":"FeatureCollection",
          "features":[{
            "type":"Feature",
            "id":"NL-NUO-0bf8edee-f912-4891-8b4c-ad709ff523fb",
            "geometry":{"type":"Point","coordinates":[5.529763,51.767547]},
            "properties":{
              "address":"Schietspoel 18, Oss",
              "operator_name":"Vattenfall InCharge",
              "last_updated":"2026-10-24T12:00:00Z",
              "availabilities":$availabilities
            }
          }]
        }"""
}
