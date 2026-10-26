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

    @Test fun identicalComponentsOnDifferentConnectorsRemainDistinct() {
        val data = """{"evses":[{"connectors":[
          {"sourceId":"1","tariffs":[{"sourceId":"T-A","presentation":{"currency":"EUR","hasUnsupportedData":false,"elements":[{"components":[{"type":"ENERGY","price":0.40}]}]}}]},
          {"sourceId":"2","tariffs":[{"sourceId":"T-B","presentation":{"currency":"EUR","hasUnsupportedData":false,"elements":[{"components":[{"type":"ENERGY","price":0.40}]}]}}]}
        ]}]}"""
        val result = parseEnrichment(data)
        assertEquals(2, result.tariffs.size)
        assertEquals(setOf("T-A", "T-B"), result.tariffs.map { it.tariffId }.toSet())
        assertEquals(setOf("1", "2"), result.tariffs.map { it.connectorId }.toSet())
    }
    @Test fun experimentalStatisticsPreserveMissingSlots() {
        val raw = """{"isExperimental":true,"probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA","slots":[
          {"hour":8,"availableProbability":0.25,"sampleCount":5},
          {"hour":8,"availableProbability":0.75,"sampleCount":3},
          {"hour":9,"availableProbability":null,"sampleCount":8},
          {"hour":10,"availableProbability":1.2,"sampleCount":9}
        ]}"""
        val hourly = parseHourlyAvailability(raw)
        assertEquals(1, hourly.size)
        assertEquals(8, hourly.single().hour)
        assertEquals(0.5, hourly.single().share, 0.0001)
    }

    @Test fun nonExperimentalDataIsNotRendered() {
        val raw = """{"isExperimental":false,"slots":[{"hour":8,"availableProbability":0.9,"sampleCount":9}]}"""
        assertTrue(parseHourlyAvailability(raw).isEmpty())
    }
    @Test(expected = IllegalStateException::class)
    fun missingEvseContractIsNotInterpretedAsEmptyLocation() {
        parseEnrichment("""{"other":"value"}""")
    }
}
