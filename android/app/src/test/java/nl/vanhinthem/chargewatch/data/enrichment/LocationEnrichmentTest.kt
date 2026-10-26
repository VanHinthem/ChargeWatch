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

    @Test fun partiallyUnsupportedTariffKeepsComponentWithoutInventingTotal() {
        val result = parseEnrichment("""
            {"evses":[{"connectors":[{"tariffs":[
              {"presentation":{"currency":"EUR","hasUnsupportedData":true,
              "elements":[{"components":[{"type":"ENERGY","price":1}]}]}},
              {"details":{"elements":[]}}
            ]}]}]}
        """.trimIndent())
        assertTrue(result.hasComplexTariffs)
        assertEquals(1, result.tariffs.size)
        assertEquals("ENERGY", result.tariffs.single().type)
        assertEquals("1", result.tariffs.single().inclusiveAmount)
        assertFalse(result.tariffs.single().vatKnown)
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

    @Test fun supportedComponentInsidePartiallyUnsupportedTariffRemainsVisibleWithWarning() {
        val raw = """{"evses":[{"connectors":[{"tariffs":[{"presentation":{
          "currency":"EUR","hasUnsupportedData":true,
          "elements":[{"components":[{"type":"ENERGY","price":0.44,"vat":21}]}]
        }}]}]}]}"""
        val result = parseEnrichment(raw)
        assertTrue(result.hasComplexTariffs)
        assertEquals(1, result.tariffs.size)
        assertEquals("0.53", result.tariffs.single().inclusiveAmount)
    }

    @Test fun ocpiVatComponentIsConvertedForDisplay() {
        val raw = """{"evses":[{"connectors":[{"tariffs":[{"presentation":{"currency":"EUR","hasUnsupportedData":false,"elements":[{"components":[{"type":"ENERGY","price":0.2691,"vat":21}]}]}}]}]}]}"""
        val part = parseEnrichment(raw).tariffs.single()
        assertEquals("0.33", part.inclusiveAmount)
        assertTrue(part.vatKnown)
    }

    @Test fun startDateAloneIsNotPresentedAsPriceCondition() {
        val raw = """{"evses":[{"connectors":[{"tariffs":[{"presentation":{"currency":"EUR","hasUnsupportedData":false,"elements":[{"restrictions":{"start_date":"2026-01-01","end_date":null,"min_kwh":null},"components":[{"type":"ENERGY","price":0.2691,"vat":21}]}]}}]}]}]}"""
        val part = parseEnrichment(raw).tariffs.single()
        assertFalse(part.hasRestrictions)
        assertEquals("0.33", part.inclusiveAmount)
    }
    @Test fun experimentalStatisticsRemainSeparatedByWeekday() {
        val raw = """{"isExperimental":true,"probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA","slots":[
          {"isoWeekday":1,"hour":8,"availableProbability":0.25,"sampleCount":5},
          {"isoWeekday":6,"hour":8,"availableProbability":0.75,"sampleCount":3},
          {"isoWeekday":1,"hour":9,"availableProbability":null,"sampleCount":8},
          {"isoWeekday":1,"hour":10,"availableProbability":1.2,"sampleCount":9},
          {"hour":11,"availableProbability":0.4,"sampleCount":9}
        ]}"""
        val hourly = parseHourlyAvailability(raw)
        assertEquals(2, hourly.size)
        assertEquals(1, hourly[0].isoWeekday)
        assertEquals(6, hourly[1].isoWeekday)
        assertEquals(8, hourly[0].hour)
        assertEquals(0.25, hourly[0].share, 0.0001)
        assertEquals(0.75, hourly[1].share, 0.0001)
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
