package nl.vanhinthem.chargewatch.data.favorites

import java.time.Duration
import java.time.Instant

/**
 * Each favorite has its own successful retrieval timestamp. Adding one new
 * favorite must not extend the freshness window of the older favorites.
 * Missing locations are also considered checked for two minutes.
 */
internal fun favoriteIdsNeedingBatchFetch(
    ids: List<String>,
    fetchedAtById: Map<String, Instant>,
    now: Instant,
    force: Boolean
): List<String> = ids.distinct().filter { id ->
    if (force) return@filter true
    val checkedAt=fetchedAtById[id] ?: return@filter true
    checkedAt.isAfter(now) || Duration.between(checkedAt,now) > Duration.ofMinutes(2)
}
