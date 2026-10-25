package nl.vanhinthem.chargewatch.data.ndw

import java.util.Locale
import kotlin.math.roundToInt

/** NDW power_max is expressed in watts (e.g. 11040 W = 11.04 kW). */
fun formatChargingPower(watts: Double?): String? {
    if (watts == null || !watts.isFinite() || watts <= 0.0) return null
    val kw = watts / 1000.0
    val rounded = kw.roundToInt()
    return if (kotlin.math.abs(kw - rounded) < 0.15) "$rounded kW"
    else String.format(Locale.forLanguageTag("nl-NL"), "%.1f kW", kw)
}

fun connectorDisplayName(type: String?): String = when (type?.uppercase(Locale.ROOT)) {
    "IEC_62196_T2", "TYPE_2" -> "Type 2"
    "IEC_62196_T2_COMBO", "CCS", "CCS2" -> "CCS"
    "CHADEMO" -> "CHAdeMO"
    "IEC_62196_T1", "TYPE_1" -> "Type 1"
    "TESLA_S" -> "Tesla"
    else -> "Overige aansluiting"
}

fun connectorSymbol(type: String?): String = when (type?.uppercase(Locale.ROOT)) {
    "IEC_62196_T2", "TYPE_2", "IEC_62196_T1", "TYPE_1" -> "⚡"
    "IEC_62196_T2_COMBO", "CCS", "CCS2", "CHADEMO" -> "⚡"
    else -> "🔌"
}

/** Human-readable availability without presenting unknown/estimated counts as certain. */
fun availabilityDescription(summary: AvailabilitySummary): String {
    if (summary.reliability == CountReliability.Ambiguous ||
        summary.available == null || summary.total == null) return "Beschikbaarheid onbekend"
    return if (summary.reliability == CountReliability.Estimated)
        "Naar schatting ${summary.available} van ${summary.total} vrij"
    else "${summary.available} van ${summary.total} laadpunten vrij"
}
