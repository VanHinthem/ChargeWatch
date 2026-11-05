package nl.vanhinthem.chargewatch.data.favorites

import java.time.Duration
import java.time.Instant

/** The batch contains full details/history, not just recent map availability. */
internal fun favoriteIdsNeedingBatchFetch(
    ids: List<String>,
    coveredIds: Set<String>,
    fetchedAt: Instant?,
    now: Instant,
    force: Boolean
): List<String> {
    val fresh=fetchedAt?.let {
        !it.isAfter(now) && Duration.between(it,now) <= Duration.ofMinutes(2)
    } ?: false
    return if (force || !fresh) ids.distinct() else ids.distinct().filterNot(coveredIds::contains)
}
