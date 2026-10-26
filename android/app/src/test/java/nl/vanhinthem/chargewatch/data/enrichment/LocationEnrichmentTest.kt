package nl.vanhinthem.chargewatch.data.enrichment

import org.junit.Assert.*
import org.junit.Test

class LocationEnrichmentTest {
    @Test fun parsesTariffElementsWithoutInventingTotalPrice() {
        val result = parseEnrichment("""
            {"evses":[{"connectors":[{"tariffs":[{"presentation":{
              "currency":"EUR",
              "hasUnsupportedData":false,
              "elements":[
                {"components":[{"type":"ENERGY","price":0.47,"stepSize":1}],"restrictions":{"start_time":"08:00"}},
                {"components":[{"type":"PARKING_TIME","price":0.15,"stepSize":60}],"restrictions":null}
              ]
            }}]}]}]}
        """.trimIndent())
        assertEquals(2, result.tariffs.size)
        assertEquals("0.47", result.tariffs[0].amount)
        assertEquals("ENERGY", result.tariffs[0].type)
        assertTrue(result.tariffs[0].hasRestrictions)
        assertEquals("PARKING_TIME", result.tariffs[1].type)
        assertFalse(result.statisticsAvailable)
    }

    @Test fun missingOrUnsupportedTariffDoesNotYieldFictionalPrice() {
        val result = parseEnrichment("""
            {"evses":[{"connectors":[{"tariffs":[
              {"presentation":{"currency":"EUR","hasUnsupportedData":true,
              "elements":[{"components":[{"type":"ENERGY","price":1}]}]}},
              {"details":{"elements":[]}}
            ]}]}]}
        """.trimIndent())
        assertTrue(result.hasComplexTariffs)
        assertTrue(result.tariffs.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun missingEvseContractIsNotInterpretedAsEmptyLocation() {
        parseEnrichment("""{"other":"value"}""")
    }
}
