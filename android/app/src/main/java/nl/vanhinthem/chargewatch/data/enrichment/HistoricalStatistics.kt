package nl.vanhinthem.chargewatch.data.enrichment

import kotlinx.serialization.json.*
import kotlinx.serialization.json.longOrNull

/** One response from the existing statistics endpoint, cached as a unit. */
data class HistoricalStatistics(
    val slots: List<HourlyAvailability>,
    val reliability: ReliabilityStats?,
    val evses: List<EvseReliability>
) : List<HourlyAvailability> by slots

data class ReliabilityStats(
    val operationalPercent: Double?,
    val faultPercent: Double?,
    val unknownPercent: Double?,
    val coveredSlots: Int,
    val totalSlots: Int
) {
    /** An all-UNKNOWN EVSE must never be presented as a faulty EVSE. */
    val isUnknownOnly: Boolean get() =
        unknownPercent != null && unknownPercent >= 99.95
}

data class EvseReliability(val evseId: Long, val reliability: ReliabilityStats?)

private fun parseReliability(value: JsonElement?): ReliabilityStats? {
    val obj = (value as? JsonObject) ?: return null
    val covered = obj["coveredSlots"]?.jsonPrimitive?.intOrNull ?: return null
    val total = obj["totalSlots"]?.jsonPrimitive?.intOrNull ?: return null
    if (total < 0 || covered !in 0..total) return null

    fun validPercent(name: String): Double? =
        obj[name]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..100.0 }

    val operational = validPercent("operationalPercent")
    val fault = validPercent("faultPercent")
    val unknown = validPercent("unknownPercent")
    if (covered == 0) {
        if (operational != null || fault != null || unknown != null) return null
        return ReliabilityStats(null, null, null, 0, total)
    }
    if (operational == null || fault == null || unknown == null ||
        kotlin.math.abs(operational + fault + unknown - 100.0) > 0.21)
        return null
    return ReliabilityStats(operational, fault, unknown, covered, total)
}

/** Parse the whole contract, never discard reliability by reducing it to slots. */
fun parseHistoricalStatistics(raw: String): HistoricalStatistics {
    val root = Json.parseToJsonElement(raw).jsonObject
    val slots = parseHourlyAvailability(raw)
    // Preserve the API's experimental marker. Invalid contract data is not a
    // publication-ready historical measurement.
    if (root["isExperimental"]?.jsonPrimitive?.booleanOrNull != true ||
        root["probabilityInterpretation"]?.jsonPrimitive?.contentOrNull != "UNVALIDATED_SNAPSHOT_EWMA")
        return HistoricalStatistics(emptyList(), null, emptyList())

    val reliability = parseReliability(root["reliability"])
    val evses = root["evses"]?.jsonArray.orEmpty().mapNotNull { item ->
        val evse = item as? JsonObject ?: return@mapNotNull null
        val id = evse["evseId"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it > 0 } ?: return@mapNotNull null
        EvseReliability(id,parseReliability(evse["reliability"]))
    }
    return HistoricalStatistics(slots,reliability,evses)
}
