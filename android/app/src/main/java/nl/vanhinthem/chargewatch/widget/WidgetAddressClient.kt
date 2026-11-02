package nl.vanhinthem.chargewatch.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.vanhinthem.chargewatch.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Only retrieves existing address/city metadata; it never affects EVSE counts. */
class WidgetAddressClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
) {
    suspend fun load(featureId: String): WidgetAddress? = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://") || !ID.matches(featureId)) return@withContext null
        try {
            val url = (baseUrl.trimEnd('/') + "/api/v1/locations/by-ndw-id/")
                .toHttpUrl().newBuilder().addPathSegment(featureId).build()
            http.newCall(Request.Builder().url(url)
                .header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                runCatching { parseWidgetAddress(body) }.getOrNull()
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private val ID = Regex("""[A-Za-z0-9_-]{3,128}""")
    }
}

/** The existing detail endpoint returns separate OCPI address and city fields. */
internal fun parseWidgetAddress(raw: String): WidgetAddress? {
    val root = Json.parseToJsonElement(raw).jsonObject
    val address = root["address"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
    val city = root["city"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 100 } ?: return null
    return WidgetAddress(address, city)
}
