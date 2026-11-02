package nl.vanhinthem.chargewatch.widget

import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import java.time.Duration
import java.time.Instant

data class WidgetRow(
    val featureId: String,
    val name: String,
    val label: String,
    val status: AvailabilityStatus,
    val isLastKnown: Boolean,
    val freshness: String,
    val city: String? = null
)

fun widgetRow(
    featureId: String,
    name: String,
    snapshot: WidgetSnapshot?,
    lastAttemptMillis: Long,
    nowMillis: Long,
    city: String? = null
): WidgetRow {
    if (snapshot == null) return WidgetRow(
        featureId, name, "?", AvailabilityStatus.Unknown, true, "Nog niet gecontroleerd", city
    )
    val reliability = CountReliability.entries.firstOrNull { it.name == snapshot.reliability }
        ?: CountReliability.Ambiguous
    val sum = AvailabilitySummary(snapshot.free, snapshot.total, reliability)
    val valid = snapshot.total != null && snapshot.total > 0 &&
        snapshot.free != null && snapshot.free in 0..snapshot.total &&
        reliability != CountReliability.Ambiguous
    val status = if (valid) sum.status else AvailabilityStatus.Unknown

    val observed = try { snapshot.feedObservedAt?.let(Instant::parse) } catch (_: Exception) { null }
    val observationMillis = observed?.toEpochMilli()?.takeIf { it <= nowMillis + 60_000L }
    val fetchValid = snapshot.fetchedAtMillis > 0L &&
        snapshot.fetchedAtMillis <= nowMillis + 60_000L
    val olderFetch = fetchValid && nowMillis - snapshot.fetchedAtMillis > 30L * 60_000L
    val newerFailedAttempt = lastAttemptMillis > snapshot.fetchedAtMillis
    val lastKnown = !fetchValid || olderFetch || snapshot.feedIsStale == true ||
        observationMillis == null || newerFailedAttempt

    val ageMillis = (nowMillis - (observationMillis ?: snapshot.fetchedAtMillis)).coerceAtLeast(0L)
    val minutes = Duration.ofMillis(ageMillis).toMinutes()
    val age = if (minutes < 1L) "zojuist" else "$minutes min geleden"
    val freshness = when {
        !fetchValid -> "Laatste controle onbekend"
        lastKnown -> "Laatst bekend · $age"
        else -> "Bron gecontroleerd · $age"
    }
    return WidgetRow(
        featureId, name,
        if (status == AvailabilityStatus.Unknown) "?" else sum.label,
        status, lastKnown, freshness, city
    )
}

/**
 * Existing favorites already hold NDW's address text, e.g. "Schietspoel 18, Oss".
 * Never geocode or guess a city; omit it if the address has no unambiguous suffix.
 */
fun widgetFavoriteCity(favorite: FavoriteLocation): String? {
    val address = favorite.address?.takeIf { it.isNotBlank() } ?: favorite.title
    val parts = address.split(',').map(String::trim).filter(String::isNotBlank)
    if (parts.size < 2) return null

    val withoutCountry = if (parts.last().lowercase() in setOf(
            "nederland", "the netherlands", "netherlands", "nl"
        )) parts.dropLast(1) else parts
    if (withoutCountry.size < 2) return null

    val candidate = withoutCountry.last()
        .replace(Regex("""^\d{4}\s?[A-Za-z]{2}\s+"""), "")
        .trim()
    return candidate.takeIf { it.isNotEmpty() && it.any(Char::isLetter) && it.length <= 80 }
}

/** Show user alias; otherwise keep the street but avoid repeating city in both lines. */
fun widgetFavoriteName(favorite: FavoriteLocation): String {
    favorite.alias?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    val city = widgetFavoriteCity(favorite) ?: return favorite.title
    val title = favorite.title.trim()
    return title.removeSuffix(", $city").trim().ifBlank { title }
}

/**
 * Height-aware limits prioritize legibility over the original 2/3/5-row estimates.
 * A taller launcher widget may show all five, but never shrink the status ratios.
 */
fun visibleWidgetRows(widthDp: Float, heightDp: Float): Int {
    val compact = heightDp < 190f
    val reservedDp = if (compact) 62f else 70f
    val rowDp = if (compact) 38f else 48f
    val heightCapacity = ((heightDp - reservedDp) / rowDp).toInt().coerceAtLeast(1)
    return minOf(5, if (widthDp < 230f) 2 else 5, heightCapacity)
}
