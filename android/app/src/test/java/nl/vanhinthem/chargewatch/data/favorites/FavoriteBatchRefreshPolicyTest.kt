package nl.vanhinthem.chargewatch.data.favorites

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FavoriteBatchRefreshPolicyTest {
    private val now=Instant.parse("2026-11-05T09:30:00Z")
    private val ids=listOf("NL-TST-ONE","NL-TST-TWO")

    @Test fun firstFavoritesTabOpenFetchesEverything() {
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids,emptySet(),null,now,false))
    }

    @Test fun returningWithinTwoMinutesDoesNotRefetchCachedDetails() {
        assertEquals(emptyList<String>(),favoriteIdsNeedingBatchFetch(
            ids,ids.toSet(),now.minusSeconds(119),now,false))
    }

    @Test fun newFavoriteInFreshWindowFetchesOnlyMissingId() {
        assertEquals(listOf(ids[1]),favoriteIdsNeedingBatchFetch(
            ids,setOf(ids[0]),now.minusSeconds(60),now,false))
    }

    @Test fun olderCacheFetchesAllAndManualRefreshAlwaysFetchesAll() {
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids,ids.toSet(),now.minusSeconds(121),now,false))
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids,ids.toSet(),now.minusSeconds(1),now,true))
    }

    @Test fun futureClockAndDuplicateIdsAreHandledConservatively() {
        assertEquals(ids,favoriteIdsNeedingBatchFetch(
            ids+ids[0],ids.toSet(),now.plusSeconds(10),now,false))
    }
}
