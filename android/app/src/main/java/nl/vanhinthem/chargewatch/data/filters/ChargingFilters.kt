package nl.vanhinthem.chargewatch.data.filters

import okhttp3.HttpUrl

/** Session-only; groups AND together, selections inside a group OR together. */
data class ChargingFilters(
    val connectorTypes: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val power: String? = null,
    val minFree: Int = 0,
    val operators: Set<String> = emptySet()
) {
    val active: Boolean get() = connectorTypes.isNotEmpty() || modes.isNotEmpty() ||
        power != null || minFree > 0 || operators.isNotEmpty()
    val activeCount: Int get() = listOf(
        connectorTypes.isNotEmpty(), modes.isNotEmpty(), power != null,
        minFree > 0, operators.isNotEmpty()
    ).count { it }

    fun toggleQuick(id: String): ChargingFilters = when (id) {
        "free" -> copy(minFree = if (minFree > 0) 0 else 1)
        "lt50" -> copy(power = if (power == "lt50") null else "lt50")
        "gte150" -> copy(power = if (power == "gte150") null else "gte150")
        else -> this
    }

    fun appendTo(builder: HttpUrl.Builder): HttpUrl.Builder = builder.apply {
        connectorTypes.sorted().forEach { addQueryParameter("connectorType", it) }
        modes.sorted().forEach { addQueryParameter("mode", it) }
        power?.let { addQueryParameter("power", it) }
        if (minFree > 0) addQueryParameter("minFree", minFree.toString())
        operators.sorted().forEach { addQueryParameter("operator", it) }
    }
}

fun supportsFiltering(active: Boolean, header: String?): Boolean = !active || header == "1"
