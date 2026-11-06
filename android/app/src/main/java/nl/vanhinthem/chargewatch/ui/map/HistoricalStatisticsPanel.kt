package nl.vanhinthem.chargewatch.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.vanhinthem.chargewatch.data.enrichment.EvseReliability
import nl.vanhinthem.chargewatch.data.enrichment.HistoricalStatistics
import nl.vanhinthem.chargewatch.data.enrichment.ReliabilityStats
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val availabilityGreen = Color(0xFF209668)
private val averageFreeOrange = Color(0xFFF29C38)
private val faultRed = Color(0xFFD45A50)

private fun number(value: Double): String = String.format(Locale.forLanguageTag("nl-NL"), "%.1f", value)
private fun percent(value: Double?): String = value?.let { "${number(it)}%" } ?: "Geen gegevens"

@Composable
internal fun HistoricalStatisticsPanel(
    history: HistoricalStatistics?,
    loading: Boolean,
    unavailable: Boolean,
    evseLabels: Map<Long,String>,
    selectedId: String?
) {
    Text("Historische beschikbaarheid", style = MaterialTheme.typography.titleMedium)
    if (loading) {
        Text("Historische statistieken ophalen…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (unavailable) {
        Text("Historische statistieken zijn tijdelijk niet beschikbaar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val weekdays = listOf("Ma","Di","Wo","Do","Vr","Za","Zo")
    var chosenWeekday by remember(selectedId) {
        mutableStateOf(LocalDate.now(ZoneId.of("Europe/Amsterdam")).dayOfWeek.value)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weekdays.forEachIndexed { index, label ->
            val day = index + 1
            val selected = chosenWeekday == day
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).clickable { chosenWeekday = day },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
    val slots = history?.slots.orEmpty().filter { it.isoWeekday == chosenWeekday }
    val byHour = slots.associateBy { it.hour }
    val activeMax = slots.mapNotNull { it.averageActive }.maxOrNull() ?: 0.0
    val freeMax = slots.mapNotNull { it.averageFree }.maxOrNull() ?: 0.0
    val rightMax = ceil(maxOf(1.0, activeMax, freeMax)).toInt()
    var selectedHour by remember(selectedId,chosenWeekday) { mutableStateOf<Int?>(null) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.height(112.dp), verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End) {
            Text("100%", style = MaterialTheme.typography.labelSmall)
            Text("50%", style = MaterialTheme.typography.labelSmall)
            Text("0%", style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val cellWidth = size.width / 24f
                    for (fraction in listOf(0f,0.5f,1f)) {
                        val y = size.height * fraction
                        drawLine(gridColor,Offset(0f,y),Offset(size.width,y),1.dp.toPx())
                    }
                    (0..23).forEach { hour ->
                        byHour[hour]?.share?.let { share ->
                            if (share > 0.0) {
                                val barHeight = (size.height * share).toFloat()
                                drawRect(
                                    color = availabilityGreen,
                                    topLeft = Offset(hour*cellWidth+cellWidth*0.14f,size.height-barHeight),
                                    size = androidx.compose.ui.geometry.Size(cellWidth*0.72f,barHeight)
                                )
                            }
                        }
                    }
                    var previous: Offset? = null
                    (0..23).forEach { hour ->
                        val free = byHour[hour]?.averageFree
                        if (free == null) {
                            previous = null // No interpolation across missing data.
                        } else {
                            val current = Offset(
                                (hour+0.5f)*cellWidth,
                                size.height * (1f-(free/rightMax).toFloat().coerceIn(0f,1f))
                            )
                            previous?.let { start ->
                                drawLine(averageFreeOrange,start,current,2.5.dp.toPx(),
                                    cap=StrokeCap.Round)
                            }
                            drawCircle(averageFreeOrange,2.dp.toPx(),current)
                            previous = current
                        }
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    (0..23).forEach { hour ->
                        Box(Modifier.weight(1f).fillMaxSize().clickable { selectedHour = hour })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00","06","12","18","24").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.height(112.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("$rightMax", style = MaterialTheme.typography.labelSmall)
            Text("${number(rightMax/2.0)}", style = MaterialTheme.typography.labelSmall)
            Text("0", style = MaterialTheme.typography.labelSmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("■ Minstens één vrij (%)", style = MaterialTheme.typography.labelSmall,
            color = availabilityGreen)
        Text("● Gemiddeld vrij (EVSE)", style = MaterialTheme.typography.labelSmall,
            color = averageFreeOrange)
    }
    selectedHour?.let { hour ->
        val slot = byHour[hour]
        val hourLabel = "%02d:00–%02d:00".format(Locale.forLanguageTag("nl-NL"),hour,hour+1)
        Text("$hourLabel · Beschikbaarheid: ${slot?.share?.let { "${(it*100).roundToInt()}%" } ?: "Geen gegevens"}",
            style = MaterialTheme.typography.bodySmall)
        Text("Gemiddeld vrij: ${slot?.averageFree?.let(::number) ?: "Geen gegevens"} EVSE" +
            (slot?.averageActive?.let { " · gemiddeld actief: ${number(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Text("Historische betrouwbaarheid",style = MaterialTheme.typography.titleMedium)
    ReliabilitySummaryPanel(history?.reliability)
    var expanded by remember(selectedId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable { expanded=!expanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Betrouwbaarheid per EVSE (${history?.evses?.size ?: 0})",
            style=MaterialTheme.typography.bodyMedium, fontWeight=FontWeight.Medium)
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription=if (expanded) "Inklappen" else "Uitklappen")
    }
    if (expanded) {
        if (history?.evses.isNullOrEmpty())
            Text("Geen EVSE-statistieken beschikbaar",style=MaterialTheme.typography.bodySmall)
        history?.evses.orEmpty().forEach { evse ->
            EvseReliabilityPanel(evse,evseLabels[evse.evseId] ?: "EVSE ${evse.evseId}",selectedId)
        }
    }
}

@Composable
private fun ReliabilitySummaryPanel(data: ReliabilityStats?) {
    if (data == null || data.operationalPercent == null ||
        data.faultPercent == null || data.unknownPercent == null) {
        Text("Geen gegevens", style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (data.isUnknownOnly) {
        Text("Onbekend · geen betrouwbare statusinformatie",
            style=MaterialTheme.typography.bodySmall)
    } else {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Operationeel ${percent(data.operationalPercent)}",
                style=MaterialTheme.typography.bodyMedium)
            Text("Storing ${percent(data.faultPercent)}",
                style=MaterialTheme.typography.bodyMedium)
        }
    }
    Row(Modifier.fillMaxWidth().height(9.dp).background(
        MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(5.dp))) {
        if (data.operationalPercent > 0)
            Box(Modifier.weight(data.operationalPercent.toFloat()).fillMaxSize()
                .background(availabilityGreen))
        if (data.faultPercent > 0)
            Box(Modifier.weight(data.faultPercent.toFloat()).fillMaxSize()
                .background(faultRed))
        if (data.unknownPercent > 0)
            Box(Modifier.weight(data.unknownPercent.toFloat()).fillMaxSize()
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f)))
    }
    Text("Onbekend ${percent(data.unknownPercent)} · dekking ${data.coveredSlots}/${data.totalSlots} uurvakken",
        style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EvseReliabilityPanel(evse: EvseReliability,label:String,selectedId:String?) {
    var expanded by remember(selectedId,evse.evseId) { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp),
        modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable { expanded=!expanded },
            horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EVSE ${evse.evseId}",style=MaterialTheme.typography.bodyMedium)
                Text(label,style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (evse.reliability?.isUnknownOnly == true) "Onbekend"
                else percent(evse.reliability?.operationalPercent),
                style=MaterialTheme.typography.bodyMedium)
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription=if (expanded) "Inklappen" else "Uitklappen")
        }
        ReliabilitySummaryPanel(if (expanded) evse.reliability else evse.reliability)
        if (expanded && evse.reliability != null) {
            Text("Operationeel: ${percent(evse.reliability.operationalPercent)} · " +
                "Storing: ${percent(evse.reliability.faultPercent)} · " +
                "Onbekend: ${percent(evse.reliability.unknownPercent)}",
                style=MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}
