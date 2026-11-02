package nl.vanhinthem.chargewatch.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.vanhinthem.chargewatch.data.ndw.MapLocation

private val Context.widgetDataStore by preferencesDataStore(name = "chargewatch_widgets")
private val WIDGET_STATE = stringPreferencesKey("widgets_v1")

@Serializable
data class WidgetSnapshot(
    val free: Int? = null,
    val total: Int? = null,
    val reliability: String = "Ambiguous",
    val feedObservedAt: String? = null,
    val feedIsStale: Boolean? = null,
    val fetchedAtMillis: Long = 0L
)

@Serializable
data class WidgetState(
    val configurations: Map<String, List<String>> = emptyMap(),
    val snapshots: Map<String, WidgetSnapshot> = emptyMap(),
    val lastAttemptMillis: Long = 0L,
    val refreshFailed: Boolean = false
)

/** Private device-only widget selection and small last-known availability cache. */
class WidgetStore(context: Context) {
    private val store = context.applicationContext.widgetDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val states = store.data.map { decode(it[WIDGET_STATE]) }

    suspend fun read(): WidgetState = states.first()

    suspend fun configure(widgetId: Int, ids: List<String>) = update { current ->
        current.copy(configurations = current.configurations + (widgetId.toString() to ids.distinct().take(5)))
    }

    suspend fun remove(widgetIds: IntArray) = update { current ->
        val removed = widgetIds.map(Int::toString).toSet()
        val retained = current.configurations.filterKeys { it !in removed }
        val needed = retained.values.flatten().toSet()
        current.copy(configurations = retained, snapshots = current.snapshots.filterKeys { it in needed })
    }

    suspend fun reconcile(validIds: Set<String>) = update { current ->
        val configured = current.configurations.mapValues { (_, ids) ->
            ids.distinct().filter { it in validIds }.take(5)
        }
        val needed = configured.values.flatten().toSet()
        current.copy(configurations = configured, snapshots = current.snapshots.filterKeys { it in needed })
    }

    suspend fun recordRefresh(
        locations: Map<String, MapLocation>,
        atMillis: Long,
        failed: Boolean
    ) = update { current ->
        val needed = current.configurations.values.flatten().toSet()
        val updated = current.snapshots.filterKeys { it in needed }.toMutableMap()
        locations.forEach { (id, location) ->
            if (id in needed) {
                val available = location.availability
                updated[id] = WidgetSnapshot(
                    free = available.available,
                    total = available.total,
                    reliability = available.reliability.name,
                    feedObservedAt = location.feedLastObservedAt,
                    feedIsStale = location.feedIsStale,
                    fetchedAtMillis = atMillis
                )
            }
        }
        current.copy(
            snapshots = updated,
            lastAttemptMillis = atMillis,
            refreshFailed = failed || locations.size < needed.size
        )
    }

    private suspend fun update(transform: (WidgetState) -> WidgetState) {
        store.edit { prefs ->
            prefs[WIDGET_STATE] = json.encodeToString(transform(decode(prefs[WIDGET_STATE])))
        }
    }

    private fun decode(raw: String?): WidgetState = try {
        if (raw == null) WidgetState() else json.decodeFromString<WidgetState>(raw)
    } catch (_: Exception) {
        // Corrupt local widget state cannot imply an available EVSE.
        WidgetState()
    }
}
