package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import java.time.Duration
import java.time.Instant

/** A status is displayable as current only when its source timestamp is sufficiently recent. */
data class FavoriteAvailability(
    val label: String,
    val description: String,
    val status: AvailabilityStatus,
    val sourceUpdated: String?
)

fun favoriteAvailability(location: MapLocation?, now: Instant = Instant.now()): FavoriteAvailability {
    if (location == null) return FavoriteAvailability("?", "Geen actuele gegevens", AvailabilityStatus.Unknown, null)
    val updated = try { location.lastUpdated?.let(Instant::parse) } catch (_: Exception) { null }
    if (updated == null || updated.isAfter(now.plusSeconds(60))) {
        return FavoriteAvailability("?", "Brontijd onbekend", AvailabilityStatus.Unknown, location.lastUpdated)
    }
    val age = Duration.between(updated, now)
    if (age > Duration.ofMinutes(15)) {
        return FavoriteAvailability("?", "Brongegevens verouderd (${age.toMinutes()} min)", AvailabilityStatus.Unknown, location.lastUpdated)
    }
    if (location.availability.reliability == CountReliability.Ambiguous ||
        location.availability.status == AvailabilityStatus.Unknown
    ) return FavoriteAvailability("?", "Beschikbaarheid onbekend", AvailabilityStatus.Unknown, location.lastUpdated)
    return FavoriteAvailability(
        location.availability.label,
        "Bron bijgewerkt ${age.toMinutes().coerceAtLeast(0)} min geleden",
        location.availability.status,
        location.lastUpdated
    )
}
