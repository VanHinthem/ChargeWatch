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

/** Persisted metadata from the existing read-only ChargeWatch location-details endpoint. */
@Serializable
data class WidgetAddress(val address: String, val city: String)

@Serializable
data class WidgetState(
    val configurations: Map<String, List<String>> = emptyMap(),
    val snapshots: Map<String, WidgetSnapshot> = emptyMap(),
    val addresses: Map<String, WidgetAddress> = emptyMap(),
    val addressAttempts: Map<String, Long> = emptyMap(),
    val lastAttemptMillis: Long = 0L,
    val refreshingSinceMillis: Long? = null,
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
        current.copy(configurations = retained,
            snapshots = current.snapshots.filterKeys { it in needed },
            addresses = current.addresses.filterKeys { it in needed },
            addressAttempts = current.addressAttempts.filterKeys { it in needed })
    }

    suspend fun reconcile(validIds: Set<String>) = update { current ->
        val configured = current.configurations.mapValues { (_, ids) ->
            ids.distinct().filter { it in validIds }.take(5)
        }
        val needed = configured.values.flatten().toSet()
        current.copy(configurations = configured,
            snapshots = current.snapshots.filterKeys { it in needed },
            addresses = current.addresses.filterKeys { it in needed },
            addressAttempts = current.addressAttempts.filterKeys { it in needed })
    }

    suspend fun markRefreshing(startedAtMillis: Long) = update { current ->
        current.copy(refreshingSinceMillis = startedAtMillis)
    }

    /** Save a completed small viewport immediately, without ending the whole refresh. */
    suspend fun recordIncremental(
        locations: Map<String, MapLocation>,
        atMillis: Long
    ) = update { current ->
        val needed = current.configurations.values.flatten().toSet()
        val updated = current.snapshots.toMutableMap()
        locations.forEach { (id, location) ->
            if (id in needed) {
                updated[id] = mergeWidgetSnapshot(updated[id],
                    location.toWidgetSnapshot(atMillis))
            }
        }
        current.copy(snapshots = updated.filterKeys { it in needed })
    }

    suspend fun recordRefresh(
        locations: Map<String, MapLocation>,
        atMillis: Long,
        failed: Boolean
    ) = update { current ->
        val needed = current.configurations.values.flatten().toSet()
        val updated = current.snapshots.filterKeys { it in needed }.toMutableMap()
        var invalidResults = false
        locations.forEach { (id, location) ->
            if (id !in needed) return@forEach
            val proposed = location.toWidgetSnapshot(atMillis)
            // A partial/ambiguous NDW response must never erase the previous
            // *reliable* ratio. On the next render the old ratio is "Laatst bekend".
            if (!proposed.hasReliableCounts()) invalidResults = true
            updated[id] = mergeWidgetSnapshot(updated[id], proposed)
        }
        current.copy(
            snapshots = updated,
            lastAttemptMillis = atMillis,
            refreshingSinceMillis = null,
            refreshFailed = failed || invalidResults || locations.size < needed.size
        )
    }

    /** Persist successful location metadata once; retry unsuccessful lookups infrequently. */
    suspend fun recordAddressLookup(id: String, result: WidgetAddress?, atMillis: Long) =
        update { current ->
            if (id !in current.configurations.values.flatten()) return@update current
            current.copy(
                addresses = if (result == null) current.addresses
                    else current.addresses + (id to result),
                addressAttempts = current.addressAttempts + (id to atMillis)
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

/** Never treat missing, ambiguous, inverted or nonpositive capacity as a valid ratio. */
fun WidgetSnapshot.hasReliableCounts(): Boolean =
    reliability in setOf("Safe", "Estimated") &&
    total != null && total > 0 &&
    free != null && free in 0..total

/** Limit metadata requests for failed lookups; cache valid city information indefinitely. */
fun shouldFetchWidgetAddress(
    address: WidgetAddress?,
    attemptedAtMillis: Long?,
    nowMillis: Long
): Boolean =
    (address == null || address.city.isBlank() || address.address.isBlank()) &&
    (attemptedAtMillis == null || nowMillis - attemptedAtMillis >= 60L * 60_000L)

/** An unreliable new observation cannot erase a previously trustworthy snapshot. */
fun mergeWidgetSnapshot(previous: WidgetSnapshot?, incoming: WidgetSnapshot): WidgetSnapshot =
    if (incoming.hasReliableCounts()) incoming
    else previous?.takeIf { it.hasReliableCounts() } ?: incoming

/** If Android kills an update mid-request, never show a permanent busy state. */
fun isWidgetRefreshing(state: WidgetState, nowMillis: Long): Boolean {
    val started = state.refreshingSinceMillis ?: return false
    return started > 0L && nowMillis - started in 0L..29_999L
}

/** Both partial and final results use precisely the same EVSE reliability mapping. */
private fun MapLocation.toWidgetSnapshot(atMillis: Long): WidgetSnapshot = WidgetSnapshot(
    free = availability.available,
    total = availability.total,
    reliability = availability.reliability.name,
    feedObservedAt = feedLastObservedAt,
    feedIsStale = feedIsStale,
    fetchedAtMillis = atMillis
)
