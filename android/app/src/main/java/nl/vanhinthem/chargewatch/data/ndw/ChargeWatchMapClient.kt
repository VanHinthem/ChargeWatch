package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import nl.vanhinthem.chargewatch.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Testing path; never reports incomplete viewport data as complete. */
class ChargeWatchMapClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun load(viewport: NdwViewport): NdwResult = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://"))
            return@withContext NdwResult.Unavailable("ChargeWatch API is niet geconfigureerd.")
        val url = (baseUrl.trimEnd('/') + "/api/v1/map/viewport").toHttpUrl()
            .newBuilder().addQueryParameter("bbox", viewport.bounded().asBboxParameter()).build()
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute().use { response ->
                    if (response.code == 429)
                        return@use NdwResult.RateLimited(response.header("Retry-After")?.toLongOrNull())
                    if (response.code == 413)
                        return@use NdwResult.Unavailable("Meer dan 999 locaties in dit kaartgebied. Zoom verder in.")
                    if (!response.isSuccessful)
                        return@use NdwResult.Unavailable("ChargeWatch API gaf HTTP ${response.code}.")
                    val raw = response.body?.string()
                        ?: return@use NdwResult.Unavailable("Lege API-response.")
                    runCatching { NdwResult.Success(parseChargeWatchMap(raw)) }
                        .getOrElse { NdwResult.Unavailable("Ongeldige ChargeWatch kaartgegevens.") }
                }
        } catch (_: IOException) {
            NdwResult.Unavailable("ChargeWatch API is tijdelijk niet bereikbaar.")
        } catch (_: IllegalArgumentException) {
            NdwResult.Unavailable("Ongeldige ChargeWatch API URL.")
        }
    }
}

internal fun parseChargeWatchMap(raw: String): List<MapLocation> {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["truncated"]?.jsonPrimitive?.booleanOrNull != false)
        error("Cannot prove viewport completeness")
    return root["locations"]?.jsonArray?.map { element ->
        val obj = element.jsonObject
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
        fun number(key: String) = obj[key]?.jsonPrimitive?.intOrNull
        val id = requireNotNull(text("featureId")) { "Missing featureId" }
        val longitude = requireNotNull(obj["longitude"]?.jsonPrimitive?.doubleOrNull)
        val latitude = requireNotNull(obj["latitude"]?.jsonPrimitive?.doubleOrNull)
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0)
        val total = requireNotNull(number("totalCount"))
        val available = requireNotNull(number("availableCount"))
        require(total >= 0 && available in 0..total)
        val categories = obj["connectorCategories"]?.jsonArray?.map { row ->
            val c = row.jsonObject
            NdwAvailability(
                available = c["available"]?.jsonPrimitive?.intOrNull,
                total = c["total"]?.jsonPrimitive?.intOrNull,
                connectorType = c["connectorType"]?.jsonPrimitive?.contentOrNull,
                powerType = c["powerType"]?.jsonPrimitive?.contentOrNull,
                powerMax = c["powerMax"]?.jsonPrimitive?.doubleOrNull
            )
        }.orEmpty()
        MapLocation(
            featureId = id,
            longitude = longitude, latitude = latitude,
            title = text("address")?.takeIf(String::isNotBlank) ?: "Laadlocatie",
            address = text("address"), operatorName = text("operatorName"),
            lastUpdated = text("lastUpdated"),
            availability = AvailabilitySummary(available,total,CountReliability.Safe),
            connectorCategories = categories
        )
    } ?: error("Missing locations")
}
