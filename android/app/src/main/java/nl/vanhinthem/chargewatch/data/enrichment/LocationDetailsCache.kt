package nl.vanhinthem.chargewatch.data.enrichment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.vanhinthem.chargewatch.data.favorites.FavoriteBatchClient
import nl.vanhinthem.chargewatch.data.favorites.FavoriteBatchLocation
import nl.vanhinthem.chargewatch.data.favorites.FavoriteBatchResult
import nl.vanhinthem.chargewatch.data.favorites.favoriteIdsNeedingBatchFetch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process, shared per-NDW-ID cache for the map sheet and Favorites.
 * Compact requests never download historical slots. History has a separate
 * TTL and is retrieved only on demand; failed responses are never cached.
 *
 * The same instance must be passed to all sheet entry points.
 */
class LocationDetailsCache(
    private val batchClient: FavoriteBatchClient = FavoriteBatchClient(),
    private val enrichmentClient: LocationEnrichmentClient = LocationEnrichmentClient(),
    private val now: () -> Instant = Instant::now
) {
    private val detailMutex = Mutex()
    private val historyMutex = Mutex()
    private val detail = ConcurrentHashMap<String, FavoriteBatchLocation>()
    private val checkedAt = ConcurrentHashMap<String, Instant>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val history = ConcurrentHashMap<String, CachedHistory>()

    private data class CachedHistory(val slots: List<HourlyAvailability>, val checkedAt: Instant)

    fun needingDetails(ids: List<String>, force: Boolean = false): List<String> =
        favoriteIdsNeedingBatchFetch(ids, checkedAt.toMap(), now(), force)

    fun peekDetails(id: String): FavoriteBatchLocation? =
        if (isFresh(checkedAt[id], DETAIL_TTL)) detail[id] else null

    fun peekHistory(id: String): List<HourlyAvailability>? =
        history[id]?.takeIf { isFresh(it.checkedAt, HISTORY_TTL) }?.slots

    suspend fun loadDetails(ids: List<String>, force: Boolean = false): FavoriteBatchResult =
        detailMutex.withLock {
            val unique = ids.distinct()
            require(unique.isNotEmpty())
            for (chunk in needingDetails(unique, force).chunked(FavoriteBatchClient.MAX_BATCH)) {
                when (val result = batchClient.load(chunk, includeStatistics = false)) {
                    is FavoriteBatchResult.Unavailable -> return@withLock result
                    is FavoriteBatchResult.Success -> {
                        val updated = now()
                        result.locations.forEach { (id, value) ->
                            detail[id] = value
                            missing.remove(id)
                        }
                        result.missingIds.forEach { id ->
                            detail.remove(id)
                            history.remove(id)
                            missing.add(id)
                        }
                        chunk.forEach { checkedAt[it] = updated }
                    }
                }
            }
            trimDetails()
            FavoriteBatchResult.Success(
                unique.mapNotNull { id -> detail[id]?.let { id to it } }.toMap(),
                unique.filterTo(linkedSetOf()) { it in missing }
            )
        }

    suspend fun loadHistory(id: String): List<HourlyAvailability>? =
        historyMutex.withLock {
            peekHistory(id)?.let { return@withLock it }
            val fetched = enrichmentClient.loadStatistics(id) ?: return@withLock null
            history[id] = CachedHistory(fetched, now())
            trimHistory()
            fetched
        }

    private fun isFresh(checked: Instant?, ttl: Duration): Boolean {
        if (checked == null) return false
        val age = Duration.between(checked, now())
        return !age.isNegative && age <= ttl
    }

    private fun trimDetails() {
        if (checkedAt.size <= MAX_DETAILS) return
        checkedAt.entries.sortedBy { it.value }.take(checkedAt.size - MAX_DETAILS).forEach {
            checkedAt.remove(it.key)
            detail.remove(it.key)
            missing.remove(it.key)
        }
    }

    private fun trimHistory() {
        if (history.size <= MAX_HISTORY) return
        history.entries.sortedBy { it.value.checkedAt }.take(history.size - MAX_HISTORY)
            .forEach { history.remove(it.key) }
    }

    companion object {
        private val DETAIL_TTL = Duration.ofMinutes(2)
        private val HISTORY_TTL = Duration.ofMinutes(15)
        private const val MAX_DETAILS = 120
        private const val MAX_HISTORY = 60
    }
}
