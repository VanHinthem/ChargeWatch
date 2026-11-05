package nl.vanhinthem.chargewatch.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.vanhinthem.chargewatch.MainActivity
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus

const val WIDGET_FAVORITE_PARAM = "widget_feature_id"

/** Data URI includes widgetId and featureId so rows cannot share a PendingIntent identity. */
fun widgetFavoriteIntent(context: Context, widgetId: Int, featureId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = "nl.vanhinthem.chargewatch.OPEN_WIDGET_FAVORITE"
        data = Uri.Builder().scheme("chargewatch").authority("widget")
            .appendPath(widgetId.toString()).appendPath(featureId).build()
        putExtra(WIDGET_FAVORITE_PARAM, featureId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

private fun homeIntent(context: Context, widgetId: Int) =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        data = Uri.parse("chargewatch://widget/$widgetId/home")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

class ChargeWatchWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(
            // One UI can give a 2x2 tile only ~100x62dp. Without this size
            // Glance falls back to the 260x62 4x1 layout and truncates names.
            DpSize(100.dp, 62.dp), // narrow One UI tile: one favorite, stacked
            DpSize(150.dp, 62.dp), // intermediate horizontal One UI tile
            DpSize(260.dp, 62.dp), // wide 4x1: existing horizontal layout
            DpSize(150.dp, 140.dp), DpSize(285.dp, 140.dp),
            DpSize(285.dp, 215.dp), DpSize(285.dp, 270.dp), DpSize(285.dp, 320.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val widgetStore = WidgetStore(context)
        val favoritesRepository = FavoritesRepository(context)
        val initialState = widgetStore.read()
        val initialFavorites = favoritesRepository.favorites.first()
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .getString("theme", "system")
        val dark = when (appearance) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        // An updateAll() may reuse a running Glance composition. Observe DataStore
        // *inside* provideContent so saved selections, aliases and fetched counts
        // immediately recompose instead of keeping an old pre-composition snapshot.
        provideContent {
            val state by widgetStore.states.collectAsState(initial = initialState)
            val favorites by favoritesRepository.favorites.collectAsState(initial = initialFavorites)
            val rows = state.configurations[widgetId.toString()].orEmpty()
                .mapNotNull { featureId ->
                    favorites.find { it.featureId == featureId }?.let { favorite ->
                        val detail = state.addresses[featureId]
                        widgetRow(favorite.featureId, widgetFavoriteName(favorite, detail),
                            state.snapshots[featureId], state.lastAttemptMillis,
                            System.currentTimeMillis(),
                            subtitle = widgetFavoriteSubtitle(favorite, detail),
                            city = widgetFavoriteCity(favorite, detail))
                    }
                }
            WidgetContent(context, widgetId, rows, favorites.isNotEmpty(), dark,
                refreshing = isWidgetRefreshing(state, System.currentTimeMillis()))
        }
    }
}

@Composable
private fun WidgetContent(context: Context, widgetId: Int, rows: List<WidgetRow>,
                          hasFavorites: Boolean, dark: Boolean, refreshing: Boolean) {
    val size = LocalSize.current
    if (isTinySquareWidget(size.width.value, size.height.value)) {
        TinySquareWidgetContent(context, widgetId, rows, hasFavorites, dark, refreshing)
        return
    }
    // The intermediate 150x62dp variant is a horizontal widget too; it must
    // not fall back to the tiny stacked tile selected by 100x62dp.
    if (isSingleRowWidget(size.width.value, size.height.value)) {
        SingleRowWidgetContent(context, widgetId, rows, hasFavorites, dark, refreshing)
        return
    }
    if (size.width.value < 230f) {
        SmallSquareWidgetContent(context, widgetId, rows, hasFavorites, dark, refreshing)
        return
    }
    val maxRows = visibleWidgetRows(size.width.value, size.height.value)
    val visible = rows.take(maxRows)
    val compact = size.height.value < 190f
    val rowHeight = if (compact) 40.dp else 50.dp
    val nameSize = if (compact) 17.sp else 19.sp
    val citySize = if (compact) 12.sp else 14.sp
    val badgeSize = if (compact) 20.sp else 22.sp
    val foreground = ColorProvider(if (dark) Color(0xFFF3F7F8) else Color(0xFF26313A))
    val secondary = ColorProvider(if (dark) Color(0xFFC0CDD4) else Color(0xFF66757F))
    val surface = if (dark) Color(0xFF26313A) else Color(0xFFF5F7F8)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface).cornerRadius(18.dp).padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ ChargeWatch",
                modifier = GlanceModifier.defaultWeight()
                    .clickable(actionStartActivity(homeIntent(context, widgetId))),
                maxLines = 1,
                style = TextStyle(color = foreground, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold))
            val freshness = widgetHeaderFreshness(rows, refreshing)
            if (freshness.isNotEmpty()) {
                Text(freshness, maxLines = 1,
                    style = TextStyle(color = secondary, fontSize = 10.sp))
            }
            Text("⟳", modifier = GlanceModifier.padding(start = 7.dp, end = 4.dp)
                .clickable(actionRunCallback<WidgetRefreshAction>()),
                style = TextStyle(color = foreground, fontSize = 23.sp))
        }
        Spacer(GlanceModifier.height(8.dp))
        if (rows.isEmpty()) {
            val label = if (hasFavorites) "Widget instellen" else "Nog geen favorieten"
            Text(label, style = TextStyle(color = secondary, fontSize = 12.sp),
                modifier = GlanceModifier.padding(vertical = 12.dp).clickable(
                    actionStartActivity(Intent(context, WidgetConfigurationActivity::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        data = Uri.parse("chargewatch://widget/$widgetId/configure")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                ))
        } else {
            // Start rows directly below the header, rather than centering them.
            visible.forEach { row ->
                val badge = when (row.status) {
                    AvailabilityStatus.Available -> if (dark) Color(0xFF214F39) else Color(0xFFD8F1E3)
                    AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFF572A30) else Color(0xFFFCE1E1)
                    AvailabilityStatus.Unknown -> if (dark) Color(0xFF414B52) else Color(0xFFE4E9ED)
                }
                val badgeText = when (row.status) {
                    AvailabilityStatus.Available -> if (dark) Color(0xFF74D7A1) else Color(0xFF168653)
                    AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFFFF8585) else Color(0xFFD43B3B)
                    AvailabilityStatus.Unknown -> if (dark) Color(0xFFD6DEE2) else Color(0xFF64727D)
                }
                Row(GlanceModifier.fillMaxWidth().height(rowHeight).clickable(
                    actionStartActivity(widgetFavoriteIntent(context, widgetId, row.featureId))
                ), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, maxLines = 1,
                            style = TextStyle(color = foreground, fontSize = nameSize,
                                fontWeight = FontWeight.Medium))
                        if (!row.subtitle.isNullOrBlank()) {
                            Text(row.subtitle, maxLines = 1,
                                style = TextStyle(color = secondary, fontSize = citySize))
                        }
                    }
                    Spacer(GlanceModifier.width(6.dp))
                    Text(row.label, maxLines = 1,
                        modifier = GlanceModifier.background(badge).cornerRadius(8.dp)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        style = TextStyle(color = ColorProvider(badgeText),
                            fontSize = badgeSize, fontWeight = FontWeight.Bold))
                    if (row.isLastKnown) {
                        Text(" !", style = TextStyle(color = secondary, fontSize = 12.sp))
                    }
                }
            }
        }
        if (rows.size > maxRows) {
            Text("+${rows.size - maxRows} meer",
                modifier = GlanceModifier.padding(start = 2.dp, top = 4.dp),
                maxLines = 1, style = TextStyle(color = secondary, fontSize = 10.sp))
        }
    }
}

/**
 * Very small square tile rendered by Samsung One UI at roughly 100x62dp.
 * Two-line titles plus a header do not fit here. Show just one favorite,
 * with a readable name, status badge and small freshness indicator.
 * Never use the 4x1 layout, which assumes far more horizontal space.
 */
@Composable
private fun TinySquareWidgetContent(
    context: Context,
    widgetId: Int,
    rows: List<WidgetRow>,
    hasFavorites: Boolean,
    dark: Boolean,
    refreshing: Boolean
) {
    val foreground = ColorProvider(if (dark) Color(0xFFF3F7F8) else Color(0xFF26313A))
    val secondary = ColorProvider(if (dark) Color(0xFFC0CDD4) else Color(0xFF66757F))
    val surface = if (dark) Color(0xFF26313A) else Color(0xFFF5F7F8)
    val favorite = rows.firstOrNull()
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface).cornerRadius(18.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val name = favorite?.name ?: if (hasFavorites) "Instellen" else "Geen favorieten"
            val open = if (favorite == null) {
                Intent(context, WidgetConfigurationActivity::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse("chargewatch://widget/$widgetId/configure")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else widgetFavoriteIntent(context, widgetId, favorite.featureId)
            Text(name, modifier = GlanceModifier.defaultWeight()
                .clickable(actionStartActivity(open)),
                maxLines = 1, style = TextStyle(color = foreground, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium))
            Text("⟳", modifier = GlanceModifier.padding(start = 2.dp)
                .clickable(actionRunCallback<WidgetRefreshAction>()),
                style = TextStyle(color = foreground, fontSize = 16.sp))
        }
        if (favorite != null) {
            val badge = when (favorite.status) {
                AvailabilityStatus.Available -> if (dark) Color(0xFF214F39) else Color(0xFFD8F1E3)
                AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFF572A30) else Color(0xFFFCE1E1)
                AvailabilityStatus.Unknown -> if (dark) Color(0xFF414B52) else Color(0xFFE4E9ED)
            }
            val badgeText = when (favorite.status) {
                AvailabilityStatus.Available -> if (dark) Color(0xFF74D7A1) else Color(0xFF168653)
                AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFFFF8585) else Color(0xFFD43B3B)
                AvailabilityStatus.Unknown -> if (dark) Color(0xFFD6DEE2) else Color(0xFF64727D)
            }
            Spacer(GlanceModifier.height(2.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(favorite.label, maxLines = 1,
                    modifier = GlanceModifier.background(badge).cornerRadius(6.dp)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .clickable(actionStartActivity(widgetFavoriteIntent(context, widgetId, favorite.featureId))),
                    style = TextStyle(color = ColorProvider(badgeText),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                if (rows.size > 1) {
                    Text("+${rows.size - 1}", maxLines = 1,
                        style = TextStyle(color = secondary, fontSize = 9.sp))
                }
            }
            Text(if (refreshing) "Verversen…" else compactWidgetFreshness(favorite),
                maxLines = 1, style = TextStyle(color = secondary, fontSize = 9.sp))
        }
    }
}

/**
 * Dedicated 2x2 layout: the favorite's name gets the full available width.
 * The availability badge sits on its own line; no street/city subtitle takes
 * room away from the name. If more favorites were selected, show +N meer.
 * Keep the freshness state even when a last-known result is shown.
 */
@Composable
private fun SmallSquareWidgetContent(
    context: Context,
    widgetId: Int,
    rows: List<WidgetRow>,
    hasFavorites: Boolean,
    dark: Boolean,
    refreshing: Boolean
) {
    val foreground = ColorProvider(if (dark) Color(0xFFF3F7F8) else Color(0xFF26313A))
    val secondary = ColorProvider(if (dark) Color(0xFFC0CDD4) else Color(0xFF66757F))
    val surface = if (dark) Color(0xFF26313A) else Color(0xFFF5F7F8)
    val primary = rows.firstOrNull()
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface).cornerRadius(18.dp)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡", modifier = GlanceModifier.defaultWeight()
                .clickable(actionStartActivity(homeIntent(context, widgetId))),
                style = TextStyle(color = foreground, fontSize = 19.sp))
            Text("⟳", modifier = GlanceModifier.padding(horizontal = 3.dp, vertical = 2.dp)
                .clickable(actionRunCallback<WidgetRefreshAction>()),
                style = TextStyle(color = foreground, fontSize = 19.sp))
        }
        Spacer(GlanceModifier.height(5.dp))
        if (primary == null) {
            Text(if (hasFavorites) "Widget instellen" else "Geen favorieten",
                maxLines = 2, style = TextStyle(color = secondary, fontSize = 12.sp))
        } else {
            val badge = when (primary.status) {
                AvailabilityStatus.Available -> if (dark) Color(0xFF214F39) else Color(0xFFD8F1E3)
                AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFF572A30) else Color(0xFFFCE1E1)
                AvailabilityStatus.Unknown -> if (dark) Color(0xFF414B52) else Color(0xFFE4E9ED)
            }
            val badgeText = when (primary.status) {
                AvailabilityStatus.Available -> if (dark) Color(0xFF74D7A1) else Color(0xFF168653)
                AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFFFF8585) else Color(0xFFD43B3B)
                AvailabilityStatus.Unknown -> if (dark) Color(0xFFD6DEE2) else Color(0xFF64727D)
            }
            Column(GlanceModifier.fillMaxWidth()
                .clickable(actionStartActivity(widgetFavoriteIntent(context, widgetId, primary.featureId)))) {
                Text(primary.name, maxLines = 2,
                    style = TextStyle(color = foreground, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(6.dp))
                Text(primary.label, maxLines = 1,
                    modifier = GlanceModifier.background(badge).cornerRadius(7.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(color = ColorProvider(badgeText),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (refreshing) "Verversen…" else if (primary == null) "ChargeWatch"
                else compactWidgetFreshness(primary),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1, style = TextStyle(color = secondary, fontSize = 10.sp)
            )
            if (rows.size > 1) {
                Text("+${rows.size - 1} meer", maxLines = 1,
                    style = TextStyle(color = secondary, fontSize = 9.sp))
            }
        }
    }
}

/**
 * Dedicated 4x1 composition. Removing the tall widget's title and footer allows
 * two readable location lines, the EVSE ratio, freshness and refresh in one cell.
 * Android controls the actual grid dimensions; this is intentionally height-driven.
 */
@Composable
private fun SingleRowWidgetContent(
    context: Context,
    widgetId: Int,
    rows: List<WidgetRow>,
    hasFavorites: Boolean,
    dark: Boolean,
    refreshing: Boolean
) {
    val primary = ColorProvider(if (dark) Color(0xFFF3F7F8) else Color(0xFF26313A))
    val secondary = ColorProvider(if (dark) Color(0xFFC0CDD4) else Color(0xFF66757F))
    val surface = if (dark) Color(0xFF26313A) else Color(0xFFF5F7F8)
    val row = rows.firstOrNull()
    val configureIntent = Intent(context, WidgetConfigurationActivity::class.java).apply {
        action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        data = Uri.parse("chargewatch://widget/$widgetId/configure")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    Row(
        modifier = GlanceModifier.fillMaxSize().background(surface).cornerRadius(18.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = GlanceModifier.clickable(actionStartActivity(homeIntent(context, widgetId))),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚡", style = TextStyle(color = primary, fontSize = 19.sp))
            if (rows.size > 1) {
                Text("+" + (rows.size - 1),
                    style = TextStyle(color = secondary, fontSize = 9.sp))
            }
        }
        Spacer(GlanceModifier.width(7.dp))
        if (row == null) {
            Text(if (hasFavorites) "Widget instellen" else "Nog geen favorieten",
                modifier = GlanceModifier.defaultWeight()
                    .clickable(actionStartActivity(configureIntent)),
                maxLines = 1, style = TextStyle(color = primary, fontSize = 14.sp))
        } else {
            val badge = when (row.status) {
                AvailabilityStatus.Available -> if (dark) Color(0xFF214F39) else Color(0xFFD8F1E3)
                AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFF572A30) else Color(0xFFFCE1E1)
                AvailabilityStatus.Unknown -> if (dark) Color(0xFF414B52) else Color(0xFFE4E9ED)
            }
            val badgeText = when (row.status) {
                AvailabilityStatus.Available -> if (dark) Color(0xFF74D7A1) else Color(0xFF168653)
                AvailabilityStatus.NoneAvailable -> if (dark) Color(0xFFFF8585) else Color(0xFFD43B3B)
                AvailabilityStatus.Unknown -> if (dark) Color(0xFFD6DEE2) else Color(0xFF64727D)
            }
            Row(
                modifier = GlanceModifier.defaultWeight().height(48.dp)
                    .clickable(actionStartActivity(widgetFavoriteIntent(context, widgetId, row.featureId))),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, maxLines = 1,
                        style = TextStyle(color = primary, fontSize = 17.sp, fontWeight = FontWeight.Medium))
                    if (!row.subtitle.isNullOrBlank()) {
                        Text(row.subtitle, maxLines = 1,
                            style = TextStyle(color = secondary, fontSize = 11.sp))
                    }
                }
                Spacer(GlanceModifier.width(5.dp))
                Column(horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, maxLines = 1,
                        modifier = GlanceModifier.background(badge).cornerRadius(8.dp)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        style = TextStyle(color = ColorProvider(badgeText),
                            fontSize = 19.sp, fontWeight = FontWeight.Bold))
                    Text(
                        (if (refreshing) "Verversen…" else compactWidgetFreshness(row)),
                        maxLines = 1, style = TextStyle(color = secondary, fontSize = 9.sp)
                    )
                }
            }
        }
        Spacer(GlanceModifier.width(5.dp))
        Text("⟳", modifier = GlanceModifier.padding(horizontal = 4.dp, vertical = 8.dp)
            .clickable(actionRunCallback<WidgetRefreshAction>()),
            style = TextStyle(color = primary, fontSize = 21.sp))
    }
}

class WidgetRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetWork.refresh(context)
    }
}

class ChargeWatchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChargeWatchWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetWork.ensure(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetStore(context).remove(appWidgetIds)
                WidgetWork.stopIfUnused(context)
            } finally {
                pending.finish()
            }
        }
    }
}
