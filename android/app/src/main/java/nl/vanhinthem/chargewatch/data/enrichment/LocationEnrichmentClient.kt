package nl.vanhinthem.chargewatch.data.enrichment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import nl.vanhinthem.chargewatch.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

data class PricePart(val type: String, val amount: String, val currency: String, val hasRestrictions: Boolean, val tariffId: String = "", val connectorId: String = "", val elementIndex: Int = 0, val vat: String? = null) {
    val inclusiveAmount: String
        get() {
            val net = amount.toBigDecimalOrNull() ?: return amount
            val rate = vat?.toBigDecimalOrNull() ?: return amount
            return net.multiply(BigDecimal.ONE.add(rate.divide(BigDecimal("100"))))
                .setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    val vatKnown: Boolean get() = vat != null
}
data class HourlyAvailability(val isoWeekday: Int, val hour: Int, val share: Double, val samples: Int)
data class LocationEnrichment(val tariffs: List<PricePart>, val hasComplexTariffs: Boolean, val statisticsAvailable: Boolean, val hourlyAvailability: List<HourlyAvailability> = emptyList())

sealed interface EnrichmentResult {
    data class Found(val value: LocationEnrichment) : EnrichmentResult
    data object Unconfigured : EnrichmentResult
    data object NotFound : EnrichmentResult
    data object Unavailable : EnrichmentResult
}

class LocationEnrichmentClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    suspend fun load(featureId: String): EnrichmentResult = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        if (base.isBlank() || !base.startsWith("https://")) return@withContext EnrichmentResult.Unconfigured
        if (!NDW_ID.matches(featureId)) return@withContext EnrichmentResult.NotFound

        // Distinct from map fetching: this client is only invoked by the open-location sheet.
        val path = "/api/v1/locations/by-ndw-id/${featureId}"
        val request = Request.Builder().url(base + path).header("Accept", "application/json").build()
        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> EnrichmentResult.NotFound
                    !response.isSuccessful -> EnrichmentResult.Unavailable
                    else -> {
                        val body = response.body?.string() ?: return@use EnrichmentResult.Unavailable
                        runCatching {
                            val detail = parseEnrichment(body)
                            val statisticsRequest = Request.Builder()
                                .url(base + path + "/statistics")
                                .header("Accept", "application/json").build()
                            val stats = runCatching {
                                httpClient.newCall(statisticsRequest).execute().use { statsResponse ->
                                    if (!statsResponse.isSuccessful) emptyList()
                                    else parseHourlyAvailability(statsResponse.body?.string().orEmpty())
                                }
                            }.getOrDefault(emptyList())
                            EnrichmentResult.Found(detail.copy(
                                statisticsAvailable = stats.isNotEmpty(),
                                hourlyAvailability = stats))
                        }.getOrDefault(EnrichmentResult.Unavailable)
                    }
                }
            }
        } catch (_: IOException) {
            EnrichmentResult.Unavailable
        } catch (_: IllegalArgumentException) {
            EnrichmentResult.Unavailable
        }
    }

    companion object {
        private val NDW_ID = Regex("""[A-Za-z0-9_-]{3,128}""")
    }
}

fun parseEnrichment(raw: String): LocationEnrichment {
    val root = Json.parseToJsonElement(raw).jsonObject
    val evses = root["evses"]?.jsonArray ?: error("Missing evses")
    val prices = linkedMapOf<String, PricePart>()
    var complex = false
    for (evse in evses) for (connector in evse.jsonObject["connectors"]?.jsonArray.orEmpty()) {
        val connectorId = connector.jsonObject["sourceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        for (tariff in connector.jsonObject["tariffs"]?.jsonArray.orEmpty()) {
            val t = tariff.jsonObject
            val tariffId = t["sourceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val presentation = t["presentation"]?.jsonObject
            if (presentation == null) { complex = true; continue }
            val currency = presentation["currency"]?.jsonPrimitive?.contentOrNull
            if (presentation["hasUnsupportedData"]?.jsonPrimitive?.booleanOrNull != false ||
                currency.isNullOrBlank()) {
                complex = true
                continue
            }
            for ((elementIndex, element) in presentation["elements"]?.jsonArray.orEmpty().withIndex()) {
                val obj = element.jsonObject
                val restrictions = obj["restrictions"]?.let { raw -> raw is JsonObject && raw.any { (key, value) -> key != "start_date" && value !is JsonNull } } == true
                for (component in obj["components"]?.jsonArray.orEmpty()) {
                    val c = component.jsonObject
                    val type = c["type"]?.jsonPrimitive?.contentOrNull
                    val amount = c["price"]?.jsonPrimitive?.contentOrNull
                    val vat = c["vat"]?.jsonPrimitive?.contentOrNull
                    if (type == null || amount == null || type !in listOf("ENERGY","TIME","PARKING_TIME","FLAT")) {
                        complex = true
                        continue
                    }
                    val key = listOf(connectorId, tariffId, elementIndex.toString(), type, amount, vat, currency, restrictions.toString(),
                        obj["restrictions"]?.toString().orEmpty(),
                        c["stepSize"]?.toString().orEmpty()).joinToString("|")
                    prices.putIfAbsent(key, PricePart(type, amount, currency, restrictions, tariffId, connectorId, elementIndex, vat))
                }
            }
        }
    }
    // Statistic percentages remain gated by server policy; this response never invents them.
    return LocationEnrichment(prices.values.toList(), complex, false)
}

/** Aggregate location slots by local hour for a compact exploratory preview.
 * Null, unsupported and explicitly nonexperimental server data are excluded.
 * Keep weekday-specific slots distinct; the UI selects an Amsterdam-local weekday.
 */
fun parseHourlyAvailability(raw: String): List<HourlyAvailability> {
    if (raw.isBlank()) return emptyList()
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["isExperimental"]?.jsonPrimitive?.booleanOrNull != true ||
        root["probabilityInterpretation"]?.jsonPrimitive?.contentOrNull != "UNVALIDATED_SNAPSHOT_EWMA")
        return emptyList()
    val slots = root["slots"]?.jsonArray ?: return emptyList()
    return slots.mapNotNull { item ->
        val slot = item.jsonObject
        val hour = slot["hour"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val isoWeekday = slot["isoWeekday"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val share = slot["availableProbability"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val samples = slot["sampleCount"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        if (isoWeekday !in 1..7 || hour !in 0..23 || !share.isFinite() || share !in 0.0..1.0 || samples <= 0)
            return@mapNotNull null
        HourlyAvailability(isoWeekday, hour, share, samples)
    }.sortedWith(compareBy(HourlyAvailability::isoWeekday, HourlyAvailability::hour))
}
