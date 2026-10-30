package nl.vanhinthem.chargewatch.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import nl.vanhinthem.chargewatch.data.filters.ChargingFilters

@Composable
fun QuickFilterRow(filters: ChargingFilters, onToggle: (String) -> Unit) {
    // Box has the same width as SearchHeader's search field; wrap-content
    // scrolling Row is centered when the three chips fit on the screen.
    // Horizontal scrolling remains available on narrow displays / large fonts.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("free", "Vrij", filters.minFree > 0),
                Triple("lt50", "<50 kW", filters.power == "lt50"),
                Triple("gte150", "≥150 kW", filters.power == "gte150")
            ).forEach { (key, label, selected) ->
                // 48 dp touch target, with a compact 36 dp visible chip.
                // Top alignment preserves the tight spacing below the search field.
                Box(
                    // No ripple on the outer 48 dp hit area: it would draw
                    // a large rectangular flash behind the visible 36 dp pill.
                    // The selected pill color remains the visual interaction feedback.
                    modifier = Modifier.height(48.dp).toggleable(
                        value = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(key) }
                    ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val chipColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                    Surface(
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = chipColor,
                        tonalElevation = 5.dp
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 15.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle)
        .padding(vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (selected) Text("Actief", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Inklappen" else "Uitklappen")
    }
    if (expanded) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

@Composable
private fun Choice(
    label: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
fun MapFiltersPanel(
    filters: ChargingFilters,
    maxHeight: Dp,
    operators: List<String>?,
    operatorLoading: Boolean,
    operatorError: String?,
    onOperatorSectionOpened: () -> Unit,
    onFiltersChanged: (ChargingFilters) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(setOf("power")) }
    var operatorQuery by remember { mutableStateOf("") }
    fun toggle(section: String) {
        expanded = if (section in expanded) expanded - section else expanded + section
    }
    LaunchedEffect("operator" in expanded) {
        if ("operator" in expanded && operators == null && !operatorLoading) onOperatorSectionOpened()
    }
    // Embedded below the search bar instead of a screen-bottom modal.
    // Limit its total height; only filter sections scroll, and values apply live.
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .pointerInput(onDismiss) {
                    var dragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragY = 0f },
                        onVerticalDrag = { change, amount ->
                            dragY += amount
                            change.consume()
                        },
                        onDragEnd = {
                            if (dragY < -24.dp.toPx()) onDismiss()
                        }
                    )
                }
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Laadlocaties filteren",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(
                enabled = filters.active,
                onClick = { onFiltersChanged(ChargingFilters()) }
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Alle filters wissen")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Filters inklappen")
            }
        }
        Spacer(Modifier.height(4.dp))
        // Independent vertical scrolling within the maximum 70%-screen panel.
        Column(
            Modifier.weight(1f, fill = false).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
                FilterSection("Aansluitingstype", "connector" in expanded,
                    filters.connectorTypes.isNotEmpty(), { toggle("connector") }) {
                    Choice("Alle aansluitingstypen", filters.connectorTypes.isEmpty()) {
                        onFiltersChanged(filters.copy(connectorTypes = emptySet()))
                    }
                    listOf("type2" to "Type 2", "ccs2" to "CCS2",
                        "chademo" to "CHAdeMO", "other" to "Overige").forEach { (id, label) ->
                        Choice(label, id in filters.connectorTypes) {
                            onFiltersChanged(filters.copy(connectorTypes =
                                if (id in filters.connectorTypes) filters.connectorTypes - id
                                else filters.connectorTypes + id))
                        }
                    }
                }
                FilterSection("Laadtype", "mode" in expanded,
                    filters.modes.isNotEmpty(), { toggle("mode") }) {
                    listOf(null to "Alle (AC en DC)", "ac" to "AC", "dc" to "DC").forEach { (value, label) ->
                        Choice(label, if (value == null) filters.modes.isEmpty()
                            else filters.modes == setOf(value)) {
                            onFiltersChanged(filters.copy(modes = if (value == null) emptySet() else setOf(value)))
                        }
                    }
                }
                FilterSection("Laadvermogen", "power" in expanded,
                    filters.power != null, { toggle("power") }) {
                    listOf(null to "Alle vermogens", "lt50" to "<50 kW",
                        "gte50" to "≥50 kW", "gte150" to "≥150 kW",
                        "gte300" to "≥300 kW").forEach { (value, label) ->
                        Choice(label, filters.power == value) { onFiltersChanged(filters.copy(power = value)) }
                    }
                }
                FilterSection("Beschikbaarheid", "free" in expanded,
                    filters.minFree > 0, { toggle("free") }) {
                    listOf(0 to "Alle locaties", 1 to "Minimaal 1 vrij",
                        2 to "Minimaal 2 vrij").forEach { (value, label) ->
                        Choice(label, filters.minFree == value) { onFiltersChanged(filters.copy(minFree = value)) }
                    }
                }
                FilterSection("Operators", "operator" in expanded,
                    filters.operators.isNotEmpty(), { toggle("operator") }) {
                    Choice("Alle operators", filters.operators.isEmpty()) {
                        onFiltersChanged(filters.copy(operators = emptySet()))
                    }
                    OutlinedTextField(value = operatorQuery, onValueChange = { operatorQuery = it },
                        label = { Text("Zoek operator") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    when {
                        operatorLoading -> Text("Operators ophalen…")
                        operatorError != null -> {
                            Text(operatorError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onOperatorSectionOpened) { Text("Opnieuw proberen") }
                        }
                        operators == null -> Text("Operatorlijst nog niet beschikbaar.")
                        else -> {
                            val visible = operators.filter { it.contains(operatorQuery.trim(), ignoreCase = true) }
                            if (visible.isEmpty()) Text("Geen operators gevonden.")
                            LazyColumn(Modifier.fillMaxWidth().height(210.dp)) {
                                items(visible, key = { it }) { operator ->
                                    Choice(operator, operator in filters.operators) {
                                        onFiltersChanged(filters.copy(operators =
                                            if (operator in filters.operators) filters.operators - operator
                                            else filters.operators + operator))
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
}
