package nl.vanhinthem.chargewatch.data.enrichment

import org.junit.Assert.*
import org.junit.Test

class HistoricalStatisticsTest {
    @Test fun oneResponseContainsChartCountLineAndReliability() {
        val stats = parseHistoricalStatistics("""
            {"isExperimental":true,"probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA",
             "slots":[
               {"isoWeekday":1,"hour":10,"availableProbability":0.7,"sampleCount":12,
                "averageAvailableEvses":1.5,"averageActiveEvses":4,
                "availableEvseSampleCount":10},
               {"isoWeekday":1,"hour":11,"availableProbability":null,"sampleCount":0,
                "averageAvailableEvses":0,"averageActiveEvses":3,
                "availableEvseSampleCount":3},
               {"isoWeekday":1,"hour":12,"availableProbability":0.3,"sampleCount":5,
                "averageAvailableEvses":null,"availableEvseSampleCount":0}
             ],
             "reliability":{"operationalPercent":91.2,"faultPercent":4.6,
                "unknownPercent":4.2,"coveredSlots":336,"totalSlots":504},
             "evses":[
                {"evseId":445,"slots":[],"reliability":{"operationalPercent":0,
                  "faultPercent":0,"unknownPercent":100,"coveredSlots":168,"totalSlots":168}},
                {"evseId":446,"slots":[],"reliability":{"operationalPercent":null,
                  "faultPercent":null,"unknownPercent":null,"coveredSlots":0,"totalSlots":168}}
              ]}
        """.trimIndent())
        assertEquals(3,stats.size)
        assertEquals(0.7,stats[0].share!!,0.0001)
        assertEquals(1.5,stats[0].averageFree!!,0.0001)
        assertNull(stats[1].share)
        assertEquals(0.0,stats[1].averageFree!!,0.0001)
        assertEquals(0.3,stats[2].share!!,0.0001)
        assertNull(stats[2].averageFree)
        assertEquals(91.2,stats.reliability!!.operationalPercent!!,0.0001)
        assertEquals(336,stats.reliability.coveredSlots)
        assertTrue(stats.evses[0].reliability!!.isUnknownOnly)
        assertNull(stats.evses[1].reliability!!.operationalPercent)
    }

    @Test fun invalidCountsAndReliabilityAreNotInvented() {
        val stats=parseHistoricalStatistics("""
            {"isExperimental":true,"probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA",
              "slots":[
                {"isoWeekday":2,"hour":4,"availableProbability":null,"sampleCount":0,
                 "averageAvailableEvses":5,"availableEvseSampleCount":0},
                {"isoWeekday":2,"hour":5,"availableProbability":null,"sampleCount":0,
                 "averageAvailableEvses":-1,"availableEvseSampleCount":5}
              ],
              "reliability":{"operationalPercent":100,"faultPercent":100,
                 "unknownPercent":0,"coveredSlots":4,"totalSlots":168},
              "evses":[]}
        """.trimIndent())
        assertTrue(stats.isEmpty())
        assertNull(stats.reliability)
    }

    @Test fun connectorLabelsUseIdsFromDetailAndDoNotGuessMissingPower() {
        val enrichment=parseEnrichment("""
            {"evses":[
              {"id":445,"connectors":[{"standard":"IEC_62196_T2_COMBO",
                   "maxElectricPower":150000,"tariffOptions":[]}]},
              {"id":446,"connectors":[]}
            ]}
        """.trimIndent())
        assertEquals("CCS · 150 kW",enrichment.evseLabels[445L])
        assertEquals("EVSE 446",enrichment.evseLabels[446L])
    }
}
