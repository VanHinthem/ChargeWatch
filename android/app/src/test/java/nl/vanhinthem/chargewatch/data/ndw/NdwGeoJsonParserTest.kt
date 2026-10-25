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
    fun preservesOperatorAndAddressForMapSelection() {
        val location = parser.parse(featureCollection(
            """[{"available":1,"total":2}]"""
        )).single()

        assertEquals("Vattenfall InCharge", location.title)
        assertEquals("Vattenfall InCharge", location.operatorName)
        assertEquals("Schietspoel 18, Oss", location.address)
    }

    @Test
    fun repeatedIdenticalCategoryCountsAreSummedOutsideFas() {
        val location = parser.parse(featureCollection(
            """[
                {"available":2,"total":4,"connector_type":"IEC_62196_T2"},
                {"available":2,"total":4,"connector_type":"IEC_62196_T2_COMBO"}
            ]"""
        )).single()

        assertEquals(4, location.availability.available)
        assertEquals(8, location.availability.total)
        assertEquals(CountReliability.Estimated, location.availability.reliability)
    }

    @Test
    fun differingConnectorCategoryCountsAreSummedOutsideFas() {
        val location = parser.parse(featureCollection(
            """[
                {"available":1,"total":2,"connector_type":"IEC_62196_T2"},
                {"available":1,"total":1,"connector_type":"IEC_62196_T2_COMBO"}
            ]"""
        )).single()

        assertEquals("2/3", location.availability.label)
        assertEquals(CountReliability.Estimated, location.availability.reliability)
        assertEquals(AvailabilityStatus.Available, location.availability.status)
    }

    @Test
    fun fasUsesHighestTotalCategoryInsteadOfSum() {
        val summary = parser.summarize(
            listOf(
                NdwAvailability(available = 5, total = 8),
                NdwAvailability(available = 1, total = 2)
            ),
            cpoId = "FAS",
            featureId = "NL-FAS-50757"
        )
        assertEquals("5/8", summary.label)
        assertEquals(CountReliability.Estimated, summary.reliability)
    }

    @Test
    fun inconsistentPartyIdentificationKeepsUnknown() {
        val summary = parser.summarize(
            listOf(NdwAvailability(1, 2), NdwAvailability(1, 2)),
            cpoId = "FAS", featureId = "NL-NUO-123"
        )
        assertEquals("?", summary.label)
        assertEquals(CountReliability.Ambiguous, summary.reliability)
    }

    @Test
    fun invalidCategoryDoesNotProducePartialCount() {
        val summary = parser.summarize(
            listOf(NdwAvailability(1, 2), NdwAvailability(null, 2)),
            cpoId = "NUO", featureId = "NL-NUO-123"
        )
        assertEquals(CountReliability.Ambiguous, summary.reliability)
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
