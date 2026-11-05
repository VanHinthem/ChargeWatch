package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.enrichment.HourlyAvailability
import org.junit.Assert.*
import org.junit.Test

class SheetStatisticsPolicyTest {
    private val slots = listOf(HourlyAvailability(1,10,0.5,12))

    @Test fun compactMapSheetNeverRequestsHistory() {
        assertFalse(shouldFetchSheetStatistics("NL-AAA-1",false,null,false))
    }

    @Test fun expandingMapSheetRequestsHistoryOnce() {
        assertTrue(shouldFetchSheetStatistics("NL-AAA-1",true,null,false))
        assertFalse(shouldFetchSheetStatistics("NL-AAA-1",true,slots,false))
        assertFalse(shouldFetchSheetStatistics("NL-AAA-1",false,slots,false))
        assertFalse(shouldFetchSheetStatistics("NL-AAA-1",true,slots,false))
    }

    @Test fun favoriteOpensExpandedAndRequestsHistoryImmediately() {
        assertTrue(shouldFetchSheetStatistics("NL-AAA-1",true,null,false))
        assertFalse(shouldFetchSheetStatistics(null,true,null,false))
    }

    @Test fun emptySuccessfulHistoryAlsoPreventsRepeatFetch() {
        assertFalse(shouldFetchSheetStatistics("NL-AAA-1",true,emptyList(),false))
    }

    @Test fun failedHistoryDoesNotRefetchOnEveryRecompose() {
        assertFalse(shouldFetchSheetStatistics("NL-AAA-1",true,null,true))
    }
}
