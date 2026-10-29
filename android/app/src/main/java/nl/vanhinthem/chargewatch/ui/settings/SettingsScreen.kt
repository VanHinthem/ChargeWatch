package nl.vanhinthem.chargewatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.vanhinthem.chargewatch.BuildConfig
import nl.vanhinthem.chargewatch.data.settings.ChargeWatchDataStatus
import java.net.URI
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Composable
fun SettingsScreen(
    themeChoice: String,
    onThemeChoice: (String) -> Unit,
    dataStatus: ChargeWatchDataStatus?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Instellingen",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        SettingsCard(title = "Weergave") {
            Text(
                "Thema",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "system" to "Systeem",
                    "light" to "Licht",
                    "dark" to "Donker"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = themeChoice == value,
                        onClick = { onThemeChoice(value) },
                        label = { Text(label) },
                        leadingIcon = if (themeChoice == value) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else null
                    )
                }
            }
        }

        SettingsCard(
            title = "ChargeWatch-data",
            trailing = {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (loading) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Data-status vernieuwen")
                        }
                    }
                }
            }
        ) {
            if (dataStatus == null) {
                Text(
                    error ?: if (loading) "Data-status laden…" else "Nog geen data-status geladen.",
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StatsGrid(dataStatus)

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                FeedStatusRow(
                    label = "Locaties",
                    timestamp = dataStatus.lastObservedPollAt,
                    stale = dataStatus.isStale
                )
                FeedStatusRow(
                    label = "Tarieven",
                    timestamp = dataStatus.tariffsFeedLastCheckedAt,
                    stale = dataStatus.tariffsFeedIsStale
                )

                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        SettingsCard(title = "Over de app") {
            InfoRow("Versie", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Build", BuildConfig.APP_GIT_SHA.take(8))
            InfoRow("API", apiHost())
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge
                )
                trailing?.invoke()
            }
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun StatsGrid(status: ChargeWatchDataStatus) {
    val format = NumberFormat.getIntegerInstance(Locale("nl", "NL"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCell("Locaties", format.format(status.locationCount), Modifier.weight(1f))
            StatCell("Laadpunten", format.format(status.evseCount), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCell("Connectoren", format.format(status.connectorCount), Modifier.weight(1f))
            StatCell("Tarieven", format.format(status.tariffCount), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeedStatusRow(label: String, timestamp: String?, stale: Boolean) {
    val statusColor = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(9.dp),
            shape = CircleShape,
            color = statusColor
        ) {}
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                feedAge(timestamp, stale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (stale) "Verouderd" else "Actueel",
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, fontWeight = FontWeight.Medium)
    }
}

internal fun feedAge(timestamp: String?, stale: Boolean, now: Instant = Instant.now()): String {
    val instant = try {
        timestamp?.let(Instant::parse)
    } catch (_: Exception) {
        null
    } ?: return "Laatste controle onbekend"

    if (instant.isAfter(now.plusSeconds(60))) return "Laatste controle onbekend"

    val minutes = Duration.between(instant, now).toMinutes().coerceAtLeast(0)
    val age = when (minutes) {
        0L -> "zojuist"
        1L -> "1 min geleden"
        else -> "$minutes min geleden"
    }
    return if (stale) "Laatste controle $age" else "Gecontroleerd $age"
}

private fun apiHost(): String = runCatching {
    URI(BuildConfig.CHARGEWATCH_API_BASE_URL).host
}.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.CHARGEWATCH_API_BASE_URL
