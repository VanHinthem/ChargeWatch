package nl.vanhinthem.chargewatch.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.vanhinthem.chargewatch.data.favorites.FavoriteRefreshResult
import nl.vanhinthem.chargewatch.data.favorites.FavoritesAvailabilityClient
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import java.util.concurrent.TimeUnit

object WidgetWork {
    private const val PERIODIC = "chargewatch_widget_periodic"
    private const val ON_DEMAND = "chargewatch_widget_one_time"

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, ChargeWatchWidgetReceiver::class.java)
        ).isNotEmpty()

    fun ensure(context: Context) {
        if (!hasWidgets(context)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
        )
    }

    fun refresh(context: Context) {
        if (!hasWidgets(context)) return
        ensure(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_DEMAND, ExistingWorkPolicy.KEEP,
            // A user-triggered refresh should also run while offline, so that the
            // previously cached ratio is immediately marked as last-known.
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        )
    }

    fun stopIfUnused(context: Context) {
        if (hasWidgets(context)) return
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(ON_DEMAND)
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val refreshMutex = Mutex() }

    override suspend fun doWork(): Result = refreshMutex.withLock { performRefresh() }

    private suspend fun performRefresh(): Result {
        val context = applicationContext
        if (!WidgetWork.hasWidgets(context)) return Result.success()
        val store = WidgetStore(context)
        val favorites = FavoritesRepository(context).favorites.first()
        store.reconcile(favorites.map { it.featureId }.toSet())
        val current = store.read()
        val selected = current.configurations.values.flatten().toSet()
        val targets = favorites.filter { it.featureId in selected }
        if (targets.isEmpty()) {
            ChargeWatchWidget().updateAll(context)
            return Result.success()
        }
        val now = System.currentTimeMillis()
        // One shared throttle for all widgets and refresh buttons. Failures remain retryable
        // at the next 60-second boundary; old successful cache timestamps are not advanced.
        // Throttle status polling, but still permit a one-time address metadata lookup
        // after an APK upgrade or when another widget was recently refreshed.
        if (now - current.lastAttemptMillis !in 0..59_999L) {
            try {
                when (val result = FavoritesAvailabilityClient().refresh(targets)) {
                    is FavoriteRefreshResult.Success ->
                        store.recordRefresh(result.locations, System.currentTimeMillis(),
                            result.locations.size < targets.size)
                    is FavoriteRefreshResult.Partial ->
                        store.recordRefresh(result.locations, System.currentTimeMillis(), true)
                }
            } catch (_: Exception) {
                store.recordRefresh(emptyMap(), System.currentTimeMillis(), true)
            }
        }
        // Addresses are separate metadata. A failed lookup cannot alter availability.
        // Reuse the existing detail endpoint only for missing cities; cache successes.
        val addressClient = WidgetAddressClient()
        var lookupCount = 0
        for (favorite in targets) {
            if (lookupCount >= 10) break
            val state = store.read()
            if (widgetFavoriteCity(favorite) == null &&
                shouldFetchWidgetAddress(
                    state.addresses[favorite.featureId],
                    state.addressAttempts[favorite.featureId], System.currentTimeMillis()
                )
            ) {
                lookupCount++
                val address = addressClient.load(favorite.featureId)
                store.recordAddressLookup(favorite.featureId, address, System.currentTimeMillis())
            }
        }
        ChargeWatchWidget().updateAll(context)
        return Result.success()
    }
}
