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
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import nl.vanhinthem.chargewatch.data.favorites.FavoriteBatchResult
import nl.vanhinthem.chargewatch.data.enrichment.SharedLocationDetails
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
 * Widget and app share the same in-process details cache. The widget persists
 * a small last-known snapshot in DataStore, so a process restart or offline
 * period never requires the full response to be kept in memory.
 *
 * One precise read-only batch request replaces geographic viewport grouping,
 * NDW fallback, individual city GETs and incremental per-region updates.
 */
internal object WidgetRefreshCoordinator {
    private val refreshMutex = Mutex()

    suspend fun refresh(context: Context, force: Boolean = false) {
        refreshMutex.withLock {
            val store = WidgetStore(context)
            val existing = store.read()
            if (!WidgetWork.hasWidgets(context) && existing.configurations.isEmpty())
                return@withLock

            val favorites = FavoritesRepository(context).favorites.first()
            store.reconcile(favorites.map { it.featureId }.toSet())
            val state = store.read()
            val selected = state.configurations.values.flatten().toSet()
            val ids = favorites.map { it.featureId }.filter { it in selected }.distinct()
            if (ids.isEmpty()) {
                ChargeWatchWidget().updateAll(context)
                return@withLock
            }

            val now = System.currentTimeMillis()
            val needsInitialData = ids.any { state.snapshots[it] == null }
            if (!shouldFetchWidgetAvailability(
                    nowMillis = now,
                    previousAttemptMillis = state.lastAttemptMillis,
                    force = force,
                    needsInitialData = needsInitialData
                )) {
                ChargeWatchWidget().updateAll(context)
                return@withLock
            }

            store.markRefreshing(now)
            ChargeWatchWidget().updateAll(context)
            try {
                when (val result = SharedLocationDetails.cache.loadDetails(ids, force)) {
                    is FavoriteBatchResult.Success ->
                        store.recordBatch(result, System.currentTimeMillis())
                    is FavoriteBatchResult.Unavailable ->
                        store.recordRefresh(emptyMap(), System.currentTimeMillis(), true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Preserve the previous verified ratio and mark it "Laatst bekend".
                store.recordRefresh(emptyMap(), System.currentTimeMillis(), true)
            }
            ChargeWatchWidget().updateAll(context)
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
