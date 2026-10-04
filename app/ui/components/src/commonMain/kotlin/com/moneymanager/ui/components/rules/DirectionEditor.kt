@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.rules.Direction

/** The direction kinds offered in a [DirectionEditor], with how each starts out when picked. */
private enum class DirectionKind(
    val label: String,
) {
    DEFAULT("The endpoint's own"),
    AMOUNT_SIGN("From the amount's sign"),
    FIELD("From a field's value"),
    OUTGOING("Always outgoing"),
}

private val Direction?.kind: DirectionKind
    get() =
        when (this) {
            null -> DirectionKind.DEFAULT
            is Direction.AmountSign -> DirectionKind.AMOUNT_SIGN
            is Direction.Field -> DirectionKind.FIELD
            Direction.Outgoing -> DirectionKind.OUTGOING
        }

/**
 * Edits a [Direction] — which way a row's money moves — for any strategy. [allowDefault] offers "the
 * endpoint's own" (null) where the owner has one (an API endpoint); [pathField] picks a field's path the
 * way the owning editor picks every path (a CSV column, a JSON dot-path).
 */
@Composable
fun DirectionEditor(
    direction: Direction?,
    onDirectionChanged: (Direction?) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
    allowDefault: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        var expanded by remember { mutableStateOf(false) }
        val kinds = DirectionKind.entries.filter { allowDefault || it != DirectionKind.DEFAULT }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
            OutlinedTextField(
                value = direction.kind.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Direction") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                enabled = enabled,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                kinds.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label) },
                        onClick = {
                            expanded = false
                            if (kind != direction.kind) {
                                onDirectionChanged(
                                    when (kind) {
                                        DirectionKind.DEFAULT -> null
                                        DirectionKind.AMOUNT_SIGN -> Direction.AmountSign()
                                        DirectionKind.FIELD -> Direction.Field(path = "", incomingValues = emptySet())
                                        DirectionKind.OUTGOING -> Direction.Outgoing
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
        when (direction) {
            is Direction.AmountSign ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = !direction.positiveIsIncoming,
                        onCheckedChange = { onDirectionChanged(direction.copy(positiveIsIncoming = !it)) },
                        enabled = enabled,
                    )
                    Text("Positive amounts are money going out", modifier = Modifier.padding(start = 4.dp))
                }
            is Direction.Field -> {
                pathField.Field(
                    "Direction field",
                    direction.path,
                    { onDirectionChanged(direction.copy(path = it)) },
                    direction.path.isBlank(),
                )
                OutlinedTextField(
                    value = direction.incomingValues.joinToString(", "),
                    onValueChange = { text ->
                        onDirectionChanged(
                            direction.copy(
                                incomingValues =
                                    text
                                        .split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .toSet(),
                            ),
                        )
                    },
                    label = { Text("Values meaning money coming in (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled,
                    isError = direction.incomingValues.isEmpty(),
                )
            }
            null, Direction.Outgoing -> Unit
        }
    }
}

/** Whether [this] direction has every input it needs. */
fun Direction?.isComplete(): Boolean = this !is Direction.Field || (path.isNotBlank() && incomingValues.isNotEmpty())
