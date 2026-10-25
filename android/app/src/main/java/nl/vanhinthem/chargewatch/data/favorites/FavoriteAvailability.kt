package nl.vanhinthem.chargewatch.data.favorites

import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import java.time.Duration
import java.time.Instant

/**
 * NDW lastUpdated is a source observation timestamp, not a guarantee that a new
 * timestamp is emitted while a charging point retains the same state.
 * Keep a known ratio visible, but distinguish age and quality from availability.
 */
data class FavoriteAvailability(
    val label: String,
    val description: String,
    val status: AvailabilityStatus,
    val sourceUpdated: String?
)

fun favoriteAvailability(location: MapLocation?, now: Instant = Instant.now()): FavoriteAvailability {
    if (location == null) {
        return FavoriteAvailability("?", "Geen gegevens ontvangen", AvailabilityStatus.Unknown, null)
    }
    if (location.availability.reliability == CountReliability.Ambiguous ||
        location.availability.status == AvailabilityStatus.Unknown
    ) {
        return FavoriteAvailability("?", "Beschikbaarheid onbekend", AvailabilityStatus.Unknown, location.lastUpdated)
    }

    val updated = try { location.lastUpdated?.let(Instant::parse) } catch (_: Exception) { null }
    val description = when {
        updated == null -> "Laatst bekende status · brontijd onbekend"
        updated.isAfter(now.plusSeconds(60)) -> "Laatst bekende status · ongeldige brontijd"
        else -> {
            val minutes = Duration.between(updated, now).toMinutes().coerceAtLeast(0)
            if (minutes > 15) "Laatst bekende status · bronupdate $minutes min geleden"
            else "Bron bijgewerkt $minutes min geleden"
        }
    }
    return FavoriteAvailability(
        location.availability.label,
        description,
        location.availability.status,
        location.lastUpdated
    )
}
