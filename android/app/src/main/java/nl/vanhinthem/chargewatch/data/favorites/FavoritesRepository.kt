package nl.vanhinthem.chargewatch.data.favorites

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.vanhinthem.chargewatch.data.ndw.MapLocation

private val Context.favoritesDataStore by preferencesDataStore("chargewatch_favorites")
private val FAVORITES = stringPreferencesKey("favorites_v1")

@Serializable
data class FavoriteLocation(
    val featureId: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val operatorName: String? = null,
    val alias: String? = null
) {
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: title

    companion object {
        fun from(location: MapLocation) = FavoriteLocation(
            location.featureId, location.title, location.latitude, location.longitude,
            location.address, location.operatorName
        )
    }
}

/** Private device storage only: never sends favorites or user aliases to the backend. */
class FavoritesRepository(context: Context) {
    private val store = context.applicationContext.favoritesDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val favorites: Flow<List<FavoriteLocation>> = store.data.map { prefs ->
        decode(prefs[FAVORITES])
    }

    suspend fun toggle(location: MapLocation) {
        store.edit { prefs ->
            val current = decode(prefs[FAVORITES])
            prefs[FAVORITES] = json.encodeToString(
                if (current.any { it.featureId == location.featureId })
                    current.filterNot { it.featureId == location.featureId }
                else current + FavoriteLocation.from(location)
            )
        }
    }

    suspend fun rename(id: String, alias: String) {
        store.edit { prefs ->
            prefs[FAVORITES] = json.encodeToString(
                decode(prefs[FAVORITES]).map { favorite ->
                    if (favorite.featureId == id) favorite.copy(alias = alias.trim().takeIf { it.isNotEmpty() })
                    else favorite
                }
            )
        }
    }


    suspend fun remove(id: String) {
        store.edit { prefs ->
            prefs[FAVORITES] = json.encodeToString(
                decode(prefs[FAVORITES]).filterNot { it.featureId == id }
            )
        }
    }

    suspend fun restore(favorite: FavoriteLocation, index: Int) {
        store.edit { prefs ->
            val current = decode(prefs[FAVORITES]).toMutableList()
            if (current.none { it.featureId == favorite.featureId }) {
                current.add(index.coerceIn(0, current.size), favorite)
            }
            prefs[FAVORITES] = json.encodeToString(current)
        }
    }

    suspend fun move(id: String, offset: Int) {
        store.edit { prefs ->
            val current = decode(prefs[FAVORITES]).toMutableList()
            val from = current.indexOfFirst { it.featureId == id }
            val to = from + offset
            if (from >= 0 && to in current.indices) {
                val item = current.removeAt(from)
                current.add(to, item)
            }
            prefs[FAVORITES] = json.encodeToString(current)
        }
    }

    private fun decode(value: String?): List<FavoriteLocation> =
        try { value?.let { json.decodeFromString<List<FavoriteLocation>>(it) } ?: emptyList() }
        catch (_: Exception) { emptyList() }
}
