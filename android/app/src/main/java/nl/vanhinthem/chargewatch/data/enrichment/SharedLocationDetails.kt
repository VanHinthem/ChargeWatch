package nl.vanhinthem.chargewatch.data.enrichment

/**
 * One process-wide cache for sheets, Favorites and Glance widget workers.
 * The widget also persists its last reliable snapshot in WidgetStore for
 * process restarts; this in-memory cache does not replace persistent storage.
 */
object SharedLocationDetails {
    val cache: LocationDetailsCache by lazy { LocationDetailsCache() }
}
