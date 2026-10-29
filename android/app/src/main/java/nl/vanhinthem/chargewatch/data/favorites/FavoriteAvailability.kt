package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import java.time.Duration
import java.time.Instant

/**
 * OCPI lastUpdated is when the source object itself changed. It is deliberately
 * not used as a freshness clock: an unchanged EVSE may keep that timestamp for
 * hours or days while the Locations feed is still being observed normally.
 *
 * The favorites UI uses feedLastObservedAt/feedIsStale instead. For the
 * ChargeWatch API this is the collector's Locations-feed observation time; for
 * direct NDW fallback it is the successful client fetch time.
 */
data class FavoriteAvailability(
    val label: String,
    val description: String,
    val status: AvailabilityStatus,
    val sourceUpdated: String?
)

fun favoriteAvailability(
    location: MapLocation?,
    now: Instant = Instant.now()
): FavoriteAvailability {
    if (location == null) {
        return FavoriteAvailability("?", "Nog niet gecontroleerd", AvailabilityStatus.Unknown, null)
    }

    val description = feedFreshnessLabel(
        observedAt = location.feedLastObservedAt,
        feedIsStale = location.feedIsStale,
        now = now
    )
    if (location.availability.reliability == CountReliability.Ambiguous ||
        location.availability.status == AvailabilityStatus.Unknown
    ) {
        return FavoriteAvailability(
            "?",
            description,
            AvailabilityStatus.Unknown,
            location.lastUpdated
        )
    }

    return FavoriteAvailability(
        location.availability.label,
        description,
        location.availability.status,
        location.lastUpdated
    )
}

internal fun feedFreshnessLabel(
    observedAt: String?,
    feedIsStale: Boolean?,
    now: Instant
): String {
    val observed = try {
        observedAt?.let(Instant::parse)
    } catch (_: Exception) {
        null
    } ?: return "Laatste controle onbekend"

    if (observed.isAfter(now.plusSeconds(60))) {
        return "Laatste controle onbekend"
    }

    val minutes = Duration.between(observed, now).toMinutes().coerceAtLeast(0)
    val age = when (minutes) {
        0L -> "zojuist"
        1L -> "1 min geleden"
        else -> "$minutes min geleden"
    }
    return if (feedIsStale == true) {
        "Gegevens mogelijk verouderd · laatste controle $age"
    } else {
        when (minutes) {
            0L -> "Zojuist gecontroleerd"
            1L -> "1 min geleden gecontroleerd"
            else -> "$minutes min geleden gecontroleerd"
        }
    }
}
