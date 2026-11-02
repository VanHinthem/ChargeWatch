package nl.vanhinthem.chargewatch.data.search

/**
 * A location's source-update age is independent of whether the currently polled
 * NDW availability counts are complete. Preserve valid counts even if its source
 * timestamp is old, but do not present them as freshly verified availability.
 *
 * Keep the existing stricter behavior for unknown EVSE states and an unverified
 * or stale feed: neither is evidence for the displayed availability ratio.
 */
internal enum class NearbyAvailabilityTone {
    Available, NoneAvailable, Unknown, OlderSource
}

internal data class NearbyAvailabilityDisplay(
    val label: String,
    val tone: NearbyAvailabilityTone
)

internal fun nearbyAvailabilityDisplay(item: SearchLocation): NearbyAvailabilityDisplay {
    if (item.unknownCount != 0 || item.totalCount <= 0 ||
        item.availableCount !in 0..item.totalCount || item.feedIsStale != false
    ) return NearbyAvailabilityDisplay("?", NearbyAvailabilityTone.Unknown)

    val count = "${item.availableCount}/${item.totalCount}"
    val tone = when {
        item.isStale == true -> NearbyAvailabilityTone.OlderSource
        item.availableCount > 0 -> NearbyAvailabilityTone.Available
        else -> NearbyAvailabilityTone.NoneAvailable
    }
    return NearbyAvailabilityDisplay(count, tone)
}
