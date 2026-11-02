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
    val subtitle: String? = null
)

fun widgetRow(
    featureId: String,
    name: String,
    snapshot: WidgetSnapshot?,
    lastAttemptMillis: Long,
    nowMillis: Long,
    subtitle: String? = null
): WidgetRow {
    if (snapshot == null) return WidgetRow(
        featureId, name, "?", AvailabilityStatus.Unknown, true, "Nog niet gecontroleerd", subtitle
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
        status, lastKnown, freshness, subtitle
    )
}

/**
 * NDW map data may contain only a street address. The existing location-details API
 * contains a separate city, cached by WidgetStore. Never guess from coordinates.
 */
fun widgetFavoriteCity(favorite: FavoriteLocation, details: WidgetAddress? = null): String? {
    details?.city?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val source = favorite.address?.takeIf { it.isNotBlank() } ?: favorite.title
    val parts = source.split(',').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val withoutCountry = if (parts.last().lowercase() in setOf(
            "nederland", "the netherlands", "netherlands", "nl"
        )) parts.dropLast(1) else parts
    if (withoutCountry.size < 2) return null
    return withoutCountry.last()
        .replace(Regex("""^\d{4}\s?[A-Za-z]{2}\s+"""), "").trim()
        .takeIf { it.isNotEmpty() && it.any { char -> char.isLetter() } && it.length <= 100 }
}

private fun widgetStreet(favorite: FavoriteLocation, details: WidgetAddress?): String {
    val stored = (details?.address?.takeIf { it.isNotBlank() }
        ?: favorite.address?.takeIf { it.isNotBlank() }
        ?: favorite.title).trim()
    val city = widgetFavoriteCity(favorite, details)
    if (city == null) return stored
    val pattern = """,\s*(?:\d{4}\s?[A-Za-z]{2}\s+)?""" +
        Regex.escape(city) + """(?:,\s*(?:Nederland|Netherlands|NL))?$"""
    return stored.replace(Regex(pattern, RegexOption.IGNORE_CASE), "").trim()
        .ifBlank { stored }
}

/** Primary: the custom name, otherwise the street/address already shown in the app. */
fun widgetFavoriteName(favorite: FavoriteLocation, details: WidgetAddress? = null): String =
    favorite.alias?.trim()?.takeIf { it.isNotBlank() } ?: widgetStreet(favorite, details)

/**
 * Secondary: alias => "street, city"; without alias => only "city".
 * If city is unavailable, an alias still gets the street beneath it.
 */
fun widgetFavoriteSubtitle(favorite: FavoriteLocation, details: WidgetAddress? = null): String? {
    val city = widgetFavoriteCity(favorite, details)
    val hasAlias = !favorite.alias.isNullOrBlank()
    if (!hasAlias) return city
    val street = widgetStreet(favorite, details)
    return when {
        city != null && street.isNotBlank() -> street + ", " + city
        street.isNotBlank() -> street
        else -> city
    }
}

/**
 * Height-aware limits prioritize legibility over the original 2/3/5-row estimates.
 * A taller launcher widget may show all five, but never shrink the status ratios.
 */
fun visibleWidgetRows(widthDp: Float, heightDp: Float): Int {
    val compact = heightDp < 190f
    val reservedDp = if (compact) 58f else 65f
    val rowDp = if (compact) 40f else 50f
    val heightCapacity = ((heightDp - reservedDp) / rowDp).toInt().coerceAtLeast(1)
    return minOf(5, if (widthDp < 230f) 2 else 5, heightCapacity)
}
