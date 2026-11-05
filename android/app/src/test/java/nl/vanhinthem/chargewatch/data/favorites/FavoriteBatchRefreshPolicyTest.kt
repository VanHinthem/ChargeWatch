package nl.vanhinthem.chargewatch.data.favorites

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FavoriteBatchRefreshPolicyTest {
    private val now=Instant.parse("2026-11-05T09:30:00Z")
    private val ids=listOf("NL-TST-ONE","NL-TST-TWO")

    @Test fun firstFavoritesTabOpenFetchesEverything() {
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids,emptyMap(),now,false))
    }

    @Test fun returningWithinTwoMinutesDoesNotRefetchCachedDetails() {
        val fetched=ids.associateWith { now.minusSeconds(119) }
        assertEquals(emptyList<String>(),favoriteIdsNeedingBatchFetch(ids,fetched,now,false))
    }

    @Test fun newFavoriteInFreshWindowFetchesOnlyMissingId() {
        assertEquals(listOf(ids[1]),favoriteIdsNeedingBatchFetch(
            ids,mapOf(ids[0] to now.minusSeconds(60)),now,false))
    }

    @Test fun newerFavoriteDoesNotExtendStaleFavoriteTTL() {
        assertEquals(listOf(ids[0]),favoriteIdsNeedingBatchFetch(
            ids,mapOf(ids[0] to now.minusSeconds(125),
                      ids[1] to now.minusSeconds(10)),now,false))
    }

    @Test fun olderCacheFetchesAllAndManualRefreshAlwaysFetchesAll() {
        val outdated=ids.associateWith { now.minusSeconds(121) }
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids,outdated,now,false))
        val fresh=ids.associateWith { now.minusSeconds(1) }
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids,fresh,now,true))
    }

    @Test fun futureClockAndDuplicateIdsAreHandledConservatively() {
        val future=ids.associateWith { now.plusSeconds(10) }
        assertEquals(ids,favoriteIdsNeedingBatchFetch(ids+ids[0],future,now,false))
    }
}
