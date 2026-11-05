package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.enrichment.HourlyAvailability

/**
 * Statistics are optional history, not a prerequisite for the compact sheet.
 * A remembered result (including an empty result) is loaded at most once per
 * selected location while the same sheet remains open.
 */
internal fun shouldFetchSheetStatistics(
    selectedId: String?,
    expanded: Boolean,
    loaded: List<HourlyAvailability>?,
    unavailable: Boolean
): Boolean = selectedId != null && expanded && loaded == null && !unavailable
