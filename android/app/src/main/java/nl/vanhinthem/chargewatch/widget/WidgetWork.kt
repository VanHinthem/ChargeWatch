package nl.vanhinthem.chargewatch.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.favorites.FavoriteRefreshResult
import nl.vanhinthem.chargewatch.data.favorites.FavoritesAvailabilityClient
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import java.util.concurrent.TimeUnit

object WidgetWork {
    private const val PERIODIC = "chargewatch_widget_periodic"
    private const val ON_DEMAND = "chargewatch_widget_one_time"
    // Not tied to a configuration Activity or Glance's click callback lifetime.
    private val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * User actions and widget configuration start the fetch in the live app
     * process immediately; they do not wait for WorkManager to schedule the job.
     * Expedited WorkManager is a persistence fallback if Android kills the process.
     */
    fun refresh(context: Context, force: Boolean = true) {
        val appContext = context.applicationContext
        val boundWidgets = hasWidgets(appContext)
        // During the initial configuration Android may not yet expose the new
        // widget in getAppWidgetIds(), although its saved selection already exists.
        // A deliberate save must still trigger its first fetch immediately.
        if (!boundWidgets && !force) return
        // Do not synchronously initialize WorkManager on the widget's UI/click
        // thread. First start the actual API request; schedule persistence work
        // independently so saving a configuration can render immediately.
        activeScope.launch { WidgetRefreshCoordinator.refresh(appContext, force = force) }
        activeScope.launch {
            if (boundWidgets) ensure(appContext)
            if (force) {
                // Android 12+ expedited fallback, older devices regular backup.
                val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    ON_DEMAND, ExistingWorkPolicy.KEEP, request.build()
                )
            }
        }
    }

    fun stopIfUnused(context: Context) {
        if (hasWidgets(context)) return
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(ON_DEMAND)
    }
}

/**
 * All refresh paths share a single mutex, cache, and the existing API-first
 * FavoritesAvailabilityClient. Periodic work may be delayed by Android, but
 * an explicit user action is attempted immediately.
 */
internal object WidgetRefreshCoordinator {
    private val availabilityMutex = Mutex()
    private val addressMutex = Mutex()

    suspend fun refresh(context: Context, force: Boolean = false) {
        // Location metadata must never block a subsequent manual availability refresh.
        val targets = availabilityMutex.withLock {
            val store = WidgetStore(context)
            if (!WidgetWork.hasWidgets(context) && store.read().configurations.isEmpty()) {
                return@withLock emptyList<FavoriteLocation>()
            }
            val favorites = FavoritesRepository(context).favorites.first()
            store.reconcile(favorites.map { it.featureId }.toSet())
            val current = store.read()
            val selected = current.configurations.values.flatten().toSet()
            val targets = favorites.filter { it.featureId in selected }
            if (targets.isEmpty()) {
                ChargeWatchWidget().updateAll(context)
                return@withLock emptyList<FavoriteLocation>()
            }

            val now = System.currentTimeMillis()
            val needsInitialData = targets.any { current.snapshots[it.featureId] == null }
            if (shouldFetchWidgetAvailability(
                    nowMillis = now,
                    previousAttemptMillis = current.lastAttemptMillis,
                    force = force,
                    needsInitialData = needsInitialData
                )
            ) {
                store.markRefreshing(now)
                ChargeWatchWidget().updateAll(context)
                try {
                    when (val result = FavoritesAvailabilityClient().refresh(targets,
                        onPartialLocations = { partial ->
                            store.recordIncremental(partial, System.currentTimeMillis())
                            // A finished location is usable immediately, even while
                            // another favorite awaits API/NDW fallback.
                            ChargeWatchWidget().updateAll(context)
                        })) {
                        is FavoriteRefreshResult.Success ->
                            store.recordRefresh(result.locations, System.currentTimeMillis(),
                                result.locations.size < targets.size)
                        is FavoriteRefreshResult.Partial ->
                            store.recordRefresh(result.locations, System.currentTimeMillis(), true)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    store.recordRefresh(emptyMap(), System.currentTimeMillis(), true)
                }
            }

            ChargeWatchWidget().updateAll(context)
            targets
        }

        if (targets.isEmpty()) return
        // Address requests are independent and can take several seconds each,
        // especially on fallback. Never hold the availability lock for them.
        addressMutex.withLock {
            val store = WidgetStore(context)
            val addressClient = WidgetAddressClient()
            var attempted = 0
            for (favorite in targets) {
                if (attempted >= 10) break
                val state = store.read()
                if (widgetFavoriteCity(favorite, state.addresses[favorite.featureId]) == null &&
                    shouldFetchWidgetAddress(
                        state.addresses[favorite.featureId],
                        state.addressAttempts[favorite.featureId], System.currentTimeMillis()
                    )
                ) {
                    attempted++
                    val address = addressClient.load(favorite.featureId)
                    store.recordAddressLookup(favorite.featureId, address, System.currentTimeMillis())
                }
            }
            if (attempted > 0) ChargeWatchWidget().updateAll(context)
        }
    }
}

/**
 * Explicit refresh: no 60-second WorkManager debounce, only a short safeguard
 * against accidental rapid double taps. New selections with no cached counts
 * are fetched immediately even after another widget was recently updated.
 */
internal fun shouldFetchWidgetAvailability(
    nowMillis: Long,
    previousAttemptMillis: Long,
    force: Boolean,
    needsInitialData: Boolean
): Boolean {
    val elapsed = nowMillis - previousAttemptMillis
    if (previousAttemptMillis <= 0 || elapsed < 0) return true
    val threshold = if (force || needsInitialData) 2_000L else 60_000L
    return elapsed >= threshold
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefreshCoordinator.refresh(applicationContext)
        return Result.success()
    }
}
