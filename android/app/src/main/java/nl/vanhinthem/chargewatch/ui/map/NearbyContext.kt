package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.location.DevicePosition
import nl.vanhinthem.chargewatch.data.search.Destination
import nl.vanhinthem.chargewatch.data.search.SearchLocation

/** A nearby search origin is independent of the saved PDOK destination pin. */
sealed interface NearbyContext {
    val latitude: Double
    val longitude: Double
    data class Address(val destination: Destination) : NearbyContext {
        override val latitude: Double get() = destination.latitude
        override val longitude: Double get() = destination.longitude
    }
    data class Gps(val position: DevicePosition) : NearbyContext {
        override val latitude: Double get() = position.latitude
        override val longitude: Double get() = position.longitude
    }
    data class Alternatives(val origin: NearbyOrigin) : NearbyContext {
        override val latitude: Double get() = origin.latitude
        override val longitude: Double get() = origin.longitude
    }
}

data class NearbyOrigin(
    val featureId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)

internal fun nearbyLabel(context: NearbyContext, count: Int): String {
    val noun = if (count == 1) "locatie" else "locaties"
    return when (context) {
        is NearbyContext.Address -> nearestLocationsCountLabel(count)
        is NearbyContext.Gps -> "$count $noun bij mijn positie" + if (context.position.approximate) " (geschat)" else ""
        is NearbyContext.Alternatives -> "$count nabije " + if (count == 1) "alternatief" else "alternatieven"
    }
}

/** Existing read-only API is ordered by distance; remove origin without excluding nearby neighbors. */
internal fun visibleNearbyCandidates(context: NearbyContext, items: List<SearchLocation>): List<SearchLocation> =
    items.asSequence()
        .filter { context !is NearbyContext.Alternatives || it.featureId != context.origin.featureId }
        .distinctBy { it.featureId }
        .take(5)
        .toList()

/** One nearest result set is reusable for quick context switching, not indefinitely. */
data class NearbyCacheEntry(
    val locations: List<SearchLocation>,
    val createdNanos: Long
) {
    fun isFresh(nowNanos: Long, maxAgeNanos: Long = 120_000_000_000L): Boolean =
        nowNanos >= createdNanos && nowNanos - createdNanos <= maxAgeNanos
}
