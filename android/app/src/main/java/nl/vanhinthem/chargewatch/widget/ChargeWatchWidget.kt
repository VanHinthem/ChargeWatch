package nl.vanhinthem.chargewatch.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.runtime.Composable
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
            DpSize(150.dp, 140.dp), DpSize(285.dp, 140.dp),
            DpSize(285.dp, 215.dp), DpSize(285.dp, 270.dp), DpSize(285.dp, 320.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val state = WidgetStore(context).read()
        val favorites = FavoritesRepository(context).favorites.first()
        val selected = state.configurations[widgetId.toString()].orEmpty()
        val rows = selected.mapNotNull { featureId ->
            favorites.find { it.featureId == featureId }?.let { favorite ->
                val detail = state.addresses[featureId]
                widgetRow(favorite.featureId, widgetFavoriteName(favorite, detail),
                    state.snapshots[favorite.featureId], state.lastAttemptMillis,
                    System.currentTimeMillis(),
                    subtitle = widgetFavoriteSubtitle(favorite, detail))
            }
        }
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .getString("theme", "system")
        val dark = when (appearance) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        provideContent { WidgetContent(context, widgetId, rows, favorites.isNotEmpty(), dark) }
    }
}

@Composable
private fun WidgetContent(context: Context, widgetId: Int, rows: List<WidgetRow>,
                          hasFavorites: Boolean, dark: Boolean) {
    val size = LocalSize.current
    val maxRows = visibleWidgetRows(size.width.value, size.height.value)
    val visible = rows.take(maxRows)
    val compact = size.height.value < 190f
    val narrow = size.width.value < 230f
    val rowHeight = if (compact) 46.dp else 56.dp
    val nameSize = if (compact) 16.sp else 18.sp
    val citySize = if (compact) 12.sp else 13.sp
    val badgeSize = if (compact) 19.sp else 21.sp
    val foreground = ColorProvider(if (dark) Color(0xFFF3F7F8) else Color(0xFF26313A))
    val secondary = ColorProvider(if (dark) Color(0xFFC0CDD4) else Color(0xFF66757F))
    val surface = if (dark) Color(0xFF26313A) else Color(0xFFF5F7F8)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface).cornerRadius(18.dp).padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ ChargeWatch",
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(homeIntent(context, widgetId))),
                maxLines = 1,
                style = TextStyle(color = foreground, fontSize = if (narrow) 14.sp else 17.sp,
                    fontWeight = FontWeight.Bold))
            Text("⟳", modifier = GlanceModifier.padding(start = 8.dp, end = 4.dp)
                .clickable(actionRunCallback<WidgetRefreshAction>()),
                style = TextStyle(color = foreground, fontSize = 23.sp))
        }
        Spacer(GlanceModifier.height(3.dp))
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
        // Give the selected rows room while keeping source freshness at the bottom.
        Spacer(GlanceModifier.defaultWeight())
        val footer = when {
            rows.isEmpty() -> "Open ChargeWatch om favorieten te kiezen"
            rows.any { it.isLastKnown } -> "Deels verouderd · laatst bekend"
            rows.map { it.freshness }.distinct().size > 1 -> "Bronmoment per locatie verschilt"
            else -> rows.first().freshness
        }
        val footerText = if (narrow) footer.removePrefix("Bron gecontroleerd · ") else footer
        Row(GlanceModifier.fillMaxWidth()) {
            Text(footerText, modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                style = TextStyle(color = secondary, fontSize = if (narrow) 10.sp else 11.sp))
            if (rows.size > maxRows) Text("+${rows.size - maxRows} meer",
                style = TextStyle(color = secondary, fontSize = 10.sp))
        }
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
