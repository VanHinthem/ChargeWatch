package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import java.time.Duration
import java.time.Instant

/**
 * OCPI lastUpdated describes when the source object itself last changed. It is
 * not a freshness clock: an unchanged AVAILABLE EVSE may legitimately keep the
 * same source timestamp for hours or days.
 *
 * Favorites therefore show when ChargeWatch last fetched/checked this status.
 * The original source timestamp remains available as sourceUpdated for detail
 * or diagnostics, but is never presented as "status age".
 */
data class FavoriteAvailability(
    val label: String,
    val description: String,
    val status: AvailabilityStatus,
    val sourceUpdated: String?
)

fun favoriteAvailability(
    location: MapLocation?,
    now: Instant = Instant.now(),
    checkedAt: Instant? = null
): FavoriteAvailability {
    if (location == null) {
        return FavoriteAvailability("?", "Nog niet gecontroleerd", AvailabilityStatus.Unknown, null)
    }

    val description = checkAgeLabel(checkedAt, now)
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

internal fun checkAgeLabel(checkedAt: Instant?, now: Instant): String {
    if (checkedAt == null || checkedAt.isAfter(now.plusSeconds(60))) {
        return "Nog niet gecontroleerd"
    }

    val minutes = Duration.between(checkedAt, now).toMinutes().coerceAtLeast(0)
    return when (minutes) {
        0L -> "Zojuist gecontroleerd"
        1L -> "1 min geleden gecontroleerd"
        else -> "$minutes min geleden gecontroleerd"
    }
}
