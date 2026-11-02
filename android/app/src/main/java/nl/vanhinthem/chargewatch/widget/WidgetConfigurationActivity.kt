package nl.vanhinthem.chargewatch.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.vanhinthem.chargewatch.MainActivity
import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.favorites.FavoritesRepository
import nl.vanhinthem.chargewatch.ui.theme.ChargeWatchTheme

/** The launcher invokes this both when placing and reconfiguring a widget. */
class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(Activity.RESULT_CANCELED, result)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            provider?.className != ChargeWatchWidgetReceiver::class.java.name) {
            finish()
            return
        }
        setContent {
            ChargeWatchTheme {
                var favorites by remember { mutableStateOf<List<FavoriteLocation>?>(null) }
                var selected by remember { mutableStateOf<List<String>>(emptyList()) }
                var saving by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf(false) }
                var snapshots by remember { mutableStateOf<Map<String, WidgetSnapshot>>(emptyMap()) }
                var addresses by remember { mutableStateOf<Map<String, WidgetAddress>>(emptyMap()) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(widgetId) {
                    val loaded = FavoritesRepository(this@WidgetConfigurationActivity).favorites.first()
                    val storedState = WidgetStore(this@WidgetConfigurationActivity).read()
                    val stored = storedState.configurations[widgetId.toString()]
                    snapshots = storedState.snapshots
                    addresses = storedState.addresses
                    favorites = loaded
                    selected = (stored ?: loaded.take(5).map { it.featureId })
                        .filter { id -> loaded.any { it.featureId == id } }.distinct().take(5)
                }
                if (favorites == null) {
                    Text("Favorieten laden…", modifier = Modifier.padding(24.dp))
                } else {
                    WidgetConfigurationScreen(
                        favorites = favorites.orEmpty(),
                        selected = selected,
                        snapshots = snapshots,
                        addresses = addresses,
                        saveError = saveError,
                        saving = saving,
                        onToggle = { id ->
                            selected = if (id in selected) selected - id
                                else if (selected.size < 5) selected + id else selected
                        },
                        onMove = { id, offset ->
                            val from = selected.indexOf(id)
                            val to = from + offset
                            if (from >= 0 && to in selected.indices) {
                                val list = selected.toMutableList()
                                list.add(to, list.removeAt(from))
                                selected = list
                            }
                        },
                        onOpenApp = {
                            startActivity(Intent(this@WidgetConfigurationActivity, MainActivity::class.java))
                        },
                        onSave = {
                            if (!saving) {
                                saving = true
                                scope.launch {
                                    try {
                                        WidgetStore(this@WidgetConfigurationActivity).configure(widgetId, selected)
                                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigurationActivity)
                                            .getGlanceIdBy(widgetId)
                                        ChargeWatchWidget().update(this@WidgetConfigurationActivity, glanceId)
                                        WidgetWork.ensure(this@WidgetConfigurationActivity)
                                        WidgetWork.refresh(this@WidgetConfigurationActivity)
                                        setResult(Activity.RESULT_OK, result)
                                        finish()
                                    } catch (_: Exception) {
                                        saveError = true
                                        saving = false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigurationScreen(
    favorites: List<FavoriteLocation>, selected: List<String>, saving: Boolean,
    snapshots: Map<String, WidgetSnapshot>, addresses: Map<String, WidgetAddress>,
    saveError: Boolean,
    onToggle: (String) -> Unit, onMove: (String, Int) -> Unit,
    onOpenApp: () -> Unit, onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ChargeWatch-widget", style = MaterialTheme.typography.headlineSmall)
        Text("Kies maximaal vijf favorieten in jouw volgorde.",
            style = MaterialTheme.typography.bodyMedium)
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚡ ChargeWatch", style = MaterialTheme.typography.titleMedium)
                if (selected.isEmpty()) Text("Geen favorieten geselecteerd")
                selected.take(3).forEach { id ->
                    val favorite = favorites.find { it.featureId == id }
                    if (favorite != null) Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(widgetFavoriteName(favorite, addresses[id]),
                                maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            widgetFavoriteSubtitle(favorite, addresses[id])?.let { subtitle ->
                                Text(subtitle, maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val shown = widgetRow(favorite.featureId,
                            widgetFavoriteName(favorite, addresses[id]),
                            snapshots[id], 0L, System.currentTimeMillis(),
                            subtitle = widgetFavoriteSubtitle(favorite, addresses[id]))
                        val statusColor = when (shown.status) {
                            nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus.Available -> MaterialTheme.colorScheme.primary
                            nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus.NoneAvailable -> MaterialTheme.colorScheme.error
                            nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(shown.label, color = statusColor,
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (selected.size > 3) Text("+${selected.size - 3} meer")
            }
        }
        HorizontalDivider()
        Text("Geselecteerd (${selected.size}/5)", style = MaterialTheme.typography.titleMedium)
        selected.forEachIndexed { index, id ->
            val favorite = favorites.find { it.featureId == id } ?: return@forEachIndexed
            Row(Modifier.fillMaxWidth()) {
                Checkbox(checked = true, onCheckedChange = { onToggle(id) })
                Text(favorite.displayName, Modifier.weight(1f).padding(top = 14.dp), maxLines = 1)
                IconButton(onClick = { onMove(id, -1) }, enabled = index > 0) { Text("↑") }
                IconButton(onClick = { onMove(id, 1) }, enabled = index < selected.lastIndex) { Text("↓") }
            }
        }
        val available = favorites.filterNot { it.featureId in selected }
        if (available.isNotEmpty()) {
            Text("Overige favorieten", style = MaterialTheme.typography.titleMedium)
            available.forEach { favorite ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = false, enabled = selected.size < 5,
                        onCheckedChange = { if (it) onToggle(favorite.featureId) })
                    Text(favorite.displayName, Modifier.padding(top = 14.dp), maxLines = 1)
                }
            }
        }
        if (favorites.isEmpty()) {
            Text("Je hebt nog geen favorieten opgeslagen in ChargeWatch.")
            OutlinedButton(onClick = onOpenApp) { Text("Favorieten toevoegen") }
        }
        Spacer(Modifier.height(12.dp))
        if (saveError) Text("Opslaan mislukt. Probeer opnieuw.", color = MaterialTheme.colorScheme.error)
        Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) "Opslaan…" else "Widget opslaan")
        }
    }
}
