package nl.vanhinthem.chargewatch.data.ndw

/**
 * An on-screen cluster is a group of *locations*, not an OCPI location.
 * Only aggregate counts if every constituent location has a usable EVSE count.
 * No partial aggregate is ever presented as a complete cluster capacity.
 */
data class ClusterAvailability(
    val available: Int?,
    val total: Int?,
    val locationCount: Int
) {
    val label: String
        get() = if (available != null && total != null) "$available/$total" else "$locationCount"

    val status: AvailabilityStatus
        get() = when {
            available == null || total == null -> AvailabilityStatus.Unknown
            available > 0 -> AvailabilityStatus.Available
            total > 0 -> AvailabilityStatus.NoneAvailable
            else -> AvailabilityStatus.Unknown
        }
}

fun summarizeCluster(locations: List<MapLocation>): ClusterAvailability {
    require(locations.isNotEmpty())
    var free = 0L
    var capacity = 0L
    for (location in locations) {
        val counts = location.availability
        val available = counts.available
        val total = counts.total
        if (counts.reliability == CountReliability.Ambiguous ||
            available == null || total == null ||
            available < 0 || total <= 0 || available > total
        ) return ClusterAvailability(null, null, locations.size)

        free += available
        capacity += total
        if (free > Int.MAX_VALUE || capacity > Int.MAX_VALUE) {
            return ClusterAvailability(null, null, locations.size)
        }
    }
    return ClusterAvailability(free.toInt(), capacity.toInt(), locations.size)
}
