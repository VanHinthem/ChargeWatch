package nl.vanhinthem.chargewatch.data.enrichment

import org.junit.Assert.*
import org.junit.Test

class LocationEnrichmentTest {
    @Test fun parsesApplicableTariffOptionsWithoutInventingTotalPrice() {
        val result = parseEnrichment("""
            {"evses":[{"connectors":[{"sourceId":"1","tariffStatus":"APPLICABLE","tariffOptions":[{
              "sourceId":"T-A","status":"APPLICABLE","currency":"EUR","limitations":[],
              "components":[
                {"dimension":"ENERGY","price":0.47,"vatPercent":21,"elementIndex":0,"certainty":"Applicable"},
                {"dimension":"PARKING_TIME","price":0.15,"vatPercent":null,"elementIndex":1,"certainty":"Applicable"}
              ]
            }]}]}]}
        """.trimIndent())
        assertEquals(2, result.tariffs.size)
        assertEquals("ENERGY", result.tariffs[0].type)
        assertEquals("0.47", result.tariffs[0].amount)
        assertEquals("0.57", result.tariffs[0].inclusiveAmount)
        assertTrue(result.tariffs[0].vatKnown)
        assertFalse(result.tariffs[0].hasRestrictions)
        assertEquals(setOf("APPLICABLE"), result.tariffStatuses)
        assertFalse(result.hasComplexTariffs)
    }

    @Test fun conditionalOptionKeepsUsableComponentsAndWarning() {
        val result = parseEnrichment("""
            {"evses":[{"connectors":[{"sourceId":"1","tariffStatus":"CONDITIONAL","tariffOptions":[{
              "sourceId":"T-A","status":"CONDITIONAL","sourceTariffType":"REGULAR","currency":"EUR",
              "limitations":["CONDITIONAL","TARIFF_TYPE_SELECTION_REQUIRED"],
              "components":[{"dimension":"ENERGY","price":0.44,"vatPercent":21,
                "elementIndex":3,"certainty":"Conditional"}]
            }]}]}]}
        """.trimIndent())
        assertTrue(result.hasComplexTariffs)
        assertEquals(1, result.tariffs.size)
        assertTrue(result.tariffs.single().hasRestrictions)
        assertEquals("CONDITIONAL", result.tariffs.single().optionStatus)
        assertEquals("REGULAR", result.tariffs.single().sourceTariffType)
        assertEquals(setOf("CONDITIONAL", "TARIFF_TYPE_SELECTION_REQUIRED"), result.tariffLimitations)
        assertEquals(setOf("REGULAR"), result.sourceTariffTypes)
    }

    @Test fun sourceMissingNeverInventsPrice() {
        val result = parseEnrichment("""
            {"evses":[{"connectors":[{"sourceId":"1","tariffStatus":"SOURCE_MISSING","tariffOptions":[{
              "sourceId":"T-MISSING","status":"SOURCE_MISSING","currency":null,
              "limitations":["SOURCE_TARIFF_NOT_FOUND"],"components":[]
            }]}]}]}
        """.trimIndent())
        assertTrue(result.tariffs.isEmpty())
        assertTrue(result.hasComplexTariffs)
        assertTrue("SOURCE_MISSING" in result.tariffStatuses)
    }

    @Test fun multipleOptionsRemainDistinct() {
        val data = """{"evses":[{"connectors":[
          {"sourceId":"1","tariffStatus":"MULTIPLE_OPTIONS","tariffOptions":[
            {"sourceId":"T-A","status":"APPLICABLE","currency":"EUR","limitations":[],
             "components":[{"dimension":"ENERGY","price":0.40,"vatPercent":null,"elementIndex":0,"certainty":"Applicable"}]},
            {"sourceId":"T-B","status":"APPLICABLE","currency":"EUR","limitations":[],
             "components":[{"dimension":"ENERGY","price":0.50,"vatPercent":null,"elementIndex":0,"certainty":"Applicable"}]}
          ]}
        ]}]}"""
        val result = parseEnrichment(data)
        assertEquals(2, result.tariffs.size)
        assertEquals(setOf("T-A", "T-B"), result.tariffs.map { it.tariffId }.toSet())
        assertTrue(result.hasComplexTariffs)
        assertEquals(setOf("MULTIPLE_OPTIONS"), result.tariffStatuses)
    }

    @Test fun missingVatStaysUnknownInsteadOfAssumingDutchRate() {
        val raw = """{"evses":[{"connectors":[{"sourceId":"1","tariffStatus":"APPLICABLE","tariffOptions":[{
          "sourceId":"T-A","status":"APPLICABLE","currency":"EUR","limitations":[],
          "components":[{"dimension":"ENERGY","price":0.2691,"vatPercent":null,"elementIndex":0,"certainty":"Applicable"}]
        }]}]}]}"""
        val part = parseEnrichment(raw).tariffs.single()
        assertEquals("0.2691", part.inclusiveAmount)
        assertFalse(part.vatKnown)
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
