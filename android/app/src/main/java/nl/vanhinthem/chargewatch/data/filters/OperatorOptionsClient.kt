package nl.vanhinthem.chargewatch.data.filters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import nl.vanhinthem.chargewatch.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One lazy, read-only request; caller caches the result for the app session. */
class OperatorOptionsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        val base = BuildConfig.CHARGEWATCH_API_BASE_URL.trimEnd('/')
        require(base.startsWith("https://")) { "ChargeWatch API niet geconfigureerd." }
        client.newCall(Request.Builder().url(base + "/api/v1/operators").build())
            .execute().use { response ->
                if (!response.isSuccessful) error("Operatorlijst ophalen gaf HTTP " + response.code + ".")
                val body = response.body?.string() ?: error("Lege operatorlijst.")
                Json.parseToJsonElement(body).jsonArray.map { it.jsonPrimitive.content }
                    .filter(String::isNotBlank).distinct().sorted()
            }
    }
}
