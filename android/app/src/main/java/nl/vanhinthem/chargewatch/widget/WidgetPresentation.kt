package nl.vanhinthem.chargewatch.widget

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
    val freshness: String
)

fun widgetRow(
    featureId: String,
    name: String,
    snapshot: WidgetSnapshot?,
    lastAttemptMillis: Long,
    nowMillis: Long
): WidgetRow {
    if (snapshot == null) return WidgetRow(
        featureId, name, "?", AvailabilityStatus.Unknown, true, "Nog niet gecontroleerd"
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
        status, lastKnown, freshness
    )
}

/** Width/height in dp are launcher dependent, never assume a fixed Samsung cell size. */
fun visibleWidgetRows(widthDp: Float, heightDp: Float): Int = when {
    widthDp < 230f -> 2
    heightDp < 185f -> 3
    else -> 5
}
