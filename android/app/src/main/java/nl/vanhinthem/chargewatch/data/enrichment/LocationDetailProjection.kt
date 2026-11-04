package nl.vanhinthem.chargewatch.data.enrichment

import kotlinx.serialization.json.*
import nl.vanhinthem.chargewatch.data.ndw.*

/**
 * Adapt the existing detailed by-ndw-id payload into the map sheet, reusing
 * the exact HTTP response already fetched for tariffs (no duplicate call).
 */
internal fun parseMapLocationDetail(raw: String, seed: MapLocation): MapLocation {
    val root = Json.parseToJsonElement(raw).jsonObject
    fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    val evses = root["evses"]?.jsonArray ?: error("Missing evses")
    data class ConnectorRow(val evseId: String, val isAvailable: Boolean,
        val type: String?, val powerType: String?, val powerMax: Double?,
        val watts: Long?, val source: String?)
    val connectors = mutableListOf<ConnectorRow>()
    var operational = 0
    var available = 0
    val countedIds = mutableSetOf<String>()
    evses.forEachIndexed { index, element ->
        val evse = element.jsonObject
        val id = evse.str("id") ?: evse.str("sourceUid") ?: "idx:$index"
        if (!countedIds.add(id)) return@forEachIndexed
        val status = evse.str("status")?.trim()?.uppercase().orEmpty()
        if (status !in setOf("REMOVED","PLANNED")) {
            operational++
            if (status == "AVAILABLE") available++
        }
        if (status in setOf("REMOVED","PLANNED")) return@forEachIndexed
        for (connector in evse["connectors"]?.jsonArray.orEmpty()) {
            val c = connector.jsonObject
            connectors += ConnectorRow(id,status=="AVAILABLE",
                c.str("standard"),c.str("powerType"),
                c["maxElectricPower"]?.jsonPrimitive?.doubleOrNull,
                c["effectivePowerWatts"]?.jsonPrimitive?.longOrNull,
                c.str("powerSource"))
        }
    }
    val categories = connectors.groupBy {
        listOf(it.type,it.powerType,it.powerMax?.toString(),it.watts?.toString(),it.source).joinToString("|")
    }.values.map { rows ->
        val first = rows.first()
        NdwAvailability(
            available=rows.filter { it.isAvailable }.map { it.evseId }.distinct().size,
            total=rows.map { it.evseId }.distinct().size,
            connectorType=first.type,powerType=first.powerType,
            powerMax=first.powerMax,effectivePowerWatts=first.watts,powerSource=first.source)
    }
    val address=root.str("address")?.takeIf(String::isNotBlank)
    return seed.copy(
        title=address ?: seed.title,
        address=address ?: seed.address,
        operatorName=root.str("operatorName") ?: seed.operatorName,
        lastUpdated=root.str("sourceUpdatedAt") ?: seed.lastUpdated,
        availability=AvailabilitySummary(available,operational,CountReliability.Safe),
        connectorCategories=categories,
        feedLastObservedAt=root.str("feedLastObservedAt") ?: seed.feedLastObservedAt,
        feedIsStale=root["feedIsStale"]?.jsonPrimitive?.booleanOrNull ?: seed.feedIsStale
    )
}
