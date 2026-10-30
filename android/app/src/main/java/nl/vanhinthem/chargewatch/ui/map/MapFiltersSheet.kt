package nl.vanhinthem.chargewatch.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
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
                // The search field uses Surface with tonalElevation = 5.dp.
                // Give each chip the same opaque, elevated surface and rounded
                // shape instead of the contrasting outlined FilterChip default.
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 5.dp
                ) {
                    FilterChip(
                        selected = selected,
                        onClick = { onToggle(key) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(22.dp),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapFiltersSheet(
    initial: ChargingFilters,
    operators: List<String>?,
    operatorLoading: Boolean,
    operatorError: String?,
    onOperatorSectionOpened: () -> Unit,
    onApply: (ChargingFilters) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(setOf("power")) }
    var operatorQuery by remember { mutableStateOf("") }
    fun toggle(section: String) {
        expanded = if (section in expanded) expanded - section else expanded + section
    }
    LaunchedEffect("operator" in expanded) {
        if ("operator" in expanded && operators == null && !operatorLoading) onOperatorSectionOpened()
    }
    // Open fully: with a partially expanded sheet the footer could be off-screen.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            Text(
                "Laadlocaties filteren",
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(10.dp))
            // Only the filter groups scroll. The actions are a separate, fixed footer.
            Column(
                Modifier.weight(1f).padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FilterSection("Aansluitingstype", "connector" in expanded,
                    draft.connectorTypes.isNotEmpty(), { toggle("connector") }) {
                    Choice("Alle aansluitingstypen", draft.connectorTypes.isEmpty()) {
                        draft = draft.copy(connectorTypes = emptySet())
                    }
                    listOf("type2" to "Type 2", "ccs2" to "CCS2",
                        "chademo" to "CHAdeMO", "other" to "Overige").forEach { (id, label) ->
                        Choice(label, id in draft.connectorTypes) {
                            draft = draft.copy(connectorTypes =
                                if (id in draft.connectorTypes) draft.connectorTypes - id
                                else draft.connectorTypes + id)
                        }
                    }
                }
                FilterSection("Laadtype", "mode" in expanded,
                    draft.modes.isNotEmpty(), { toggle("mode") }) {
                    listOf(null to "Alle (AC en DC)", "ac" to "AC", "dc" to "DC").forEach { (value, label) ->
                        Choice(label, if (value == null) draft.modes.isEmpty()
                            else draft.modes == setOf(value)) {
                            draft = draft.copy(modes = if (value == null) emptySet() else setOf(value))
                        }
                    }
                }
                FilterSection("Laadvermogen", "power" in expanded,
                    draft.power != null, { toggle("power") }) {
                    listOf(null to "Alle vermogens", "lt50" to "<50 kW",
                        "gte50" to "≥50 kW", "gte150" to "≥150 kW",
                        "gte300" to "≥300 kW").forEach { (value, label) ->
                        Choice(label, draft.power == value) { draft = draft.copy(power = value) }
                    }
                }
                FilterSection("Beschikbaarheid", "free" in expanded,
                    draft.minFree > 0, { toggle("free") }) {
                    listOf(0 to "Alle locaties", 1 to "Minimaal 1 vrij",
                        2 to "Minimaal 2 vrij").forEach { (value, label) ->
                        Choice(label, draft.minFree == value) { draft = draft.copy(minFree = value) }
                    }
                }
                FilterSection("Operators", "operator" in expanded,
                    draft.operators.isNotEmpty(), { toggle("operator") }) {
                    Choice("Alle operators", draft.operators.isEmpty()) {
                        draft = draft.copy(operators = emptySet())
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
                                    Choice(operator, operator in draft.operators) {
                                        draft = draft.copy(operators =
                                            if (operator in draft.operators) draft.operators - operator
                                            else draft.operators + operator)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { draft = ChargingFilters() }) {
                            Text("Alles wissen")
                        }
                        Button(onClick = { onApply(draft) }) { Text("Toepassen") }
                    }
                }
            }
        }
    }
}
