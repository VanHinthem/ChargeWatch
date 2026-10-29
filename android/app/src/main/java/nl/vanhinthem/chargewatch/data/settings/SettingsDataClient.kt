package nl.vanhinthem.chargewatch.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import nl.vanhinthem.chargewatch.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChargeWatchDataStatus(
    val lastSuccessfulImportAt: String?,
    val lastObservedPollAt: String?,
    val ageSeconds: Double?,
    val isStale: Boolean,
    val staleAfterMinutes: Int?,
    val locationCount: Long,
    val evseCount: Long,
    val connectorCount: Long,
    val tariffCount: Long,
    val tariffsFeedLastCheckedAt: String?,
    val tariffsFeedIsStale: Boolean
)

sealed interface SettingsDataResult {
    data class Success(val status: ChargeWatchDataStatus) : SettingsDataResult
    data class Failure(val message: String) : SettingsDataResult
}

class SettingsDataClient(
    private val baseUrl: String = BuildConfig.CHARGEWATCH_API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun load(): SettingsDataResult = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://"))
            return@withContext SettingsDataResult.Failure("ChargeWatch API is niet geconfigureerd.")

        val url = (baseUrl.trimEnd('/') + "/api/v1/data-status").toHttpUrl()
        try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful)
                    return@use SettingsDataResult.Failure("ChargeWatch API gaf HTTP ${response.code}.")
                val raw = response.body?.string()
                    ?: return@use SettingsDataResult.Failure("Lege API-response.")
                runCatching { SettingsDataResult.Success(parseDataStatus(raw)) }
                    .getOrElse { SettingsDataResult.Failure("Ongeldige data-status ontvangen.") }
            }
        } catch (_: IOException) {
            SettingsDataResult.Failure("ChargeWatch API is tijdelijk niet bereikbaar.")
        } catch (_: IllegalArgumentException) {
            SettingsDataResult.Failure("Ongeldige ChargeWatch API URL.")
        }
    }
}

internal fun parseDataStatus(raw: String): ChargeWatchDataStatus {
    val root = Json.parseToJsonElement(raw).jsonObject
    fun text(name: String) = root[name]?.jsonPrimitive?.contentOrNull
    fun long(name: String) = requireNotNull(root[name]?.jsonPrimitive?.longOrNull) { "Missing $name" }
    fun bool(name: String) = requireNotNull(root[name]?.jsonPrimitive?.booleanOrNull) { "Missing $name" }

    return ChargeWatchDataStatus(
        lastSuccessfulImportAt = text("lastSuccessfulImportAt"),
        lastObservedPollAt = text("lastObservedPollAt"),
        ageSeconds = root["ageSeconds"]?.jsonPrimitive?.doubleOrNull,
        isStale = bool("isStale"),
        staleAfterMinutes = root["staleAfterMinutes"]?.jsonPrimitive?.longOrNull?.toInt(),
        locationCount = long("locationCount"),
        evseCount = long("evseCount"),
        connectorCount = long("connectorCount"),
        tariffCount = long("tariffCount"),
        tariffsFeedLastCheckedAt = text("tariffsFeedLastCheckedAt"),
        tariffsFeedIsStale = bool("tariffsFeedIsStale")
    )
}
