package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

internal const val PICKER_CANDIDATES_TAG = "match-picker-candidates"

/** How far either side of the source movement the picker looks. */
internal val PICKER_WINDOWS: List<Duration> = listOf(1.days, 3.days, 7.days, 30.days)

/**
 * Picks the real transactions a source movement ([movement], on [wallet]) records, for a source row the matcher
 * couldn't pair. Only legs no match claims can be picked (see [pickerCandidates]); the totals show how far the
 * selection is from the source's, which may differ when the two sides book fees differently.
 */
@Composable
fun MatchPickerDialog(
    sourceName: String,
    movement: List<ReconciliationLeg>,
    wallet: AccountId,
    result: ReconciliationResult,
    accountNames: Map<AccountId, String>,
    onDismiss: () -> Unit,
    onConfirm: (List<ReconciliationLeg>) -> Unit,
) {
    var window by remember { mutableStateOf(3.days) }
    var sameAssetOnly by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<ReconciliationLeg>() }
    val candidates =
        remember(result, movement, wallet, window, sameAssetOnly) { result.pickerCandidates(movement, wallet, window, sameAssetOnly) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select matching transactions") },
        text = {
            // One list for the whole body, so a phone-sized dialog scrolls rather than squeezing the candidates out.
            LazyColumn(modifier = Modifier.testTag(PICKER_CANDIDATES_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "header") { Text("In $sourceName", style = MaterialTheme.typography.labelLarge) }
                items(movement.size, key = { "movement-${movement[it].key}" }) { PickerLegLine(movement[it], accountNames) }
                item(key = "filters") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                        PICKER_WINDOWS.forEach { option ->
                            FilterChip(selected = window == option, onClick = { window = option }, label = { Text("± $option") })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sameAssetOnly, onCheckedChange = { sameAssetOnly = it })
                            Text("Same asset only")
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    item(key = "empty") { Text("No unmatched transactions on the linked accounts within ± $window.") }
                }
                items(candidates.size, key = { "candidate-${candidates[it].leg.key}" }) { index ->
                    val candidate = candidates[index]
                    val checked = candidate.leg in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().toggleable(
                                value = checked,
                                enabled = candidate.selectable,
                                role = Role.Checkbox,
                                onValueChange = { if (it) selected += candidate.leg else selected -= candidate.leg },
                            ),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = candidate.selectable)
                        Column {
                            PickerLegLine(candidate.leg, accountNames, delta = candidate.delta, enabled = candidate.selectable)
                            candidate.nearMatchFor?.let { match ->
                                Text(
                                    "Near match for $sourceName's ${formatAmount(match.sourceLeg.amount)} on " +
                                        match.sourceLeg.timestamp.displayDateTime(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "totals") {
                    val totals = selectionTotals(movement, selected)
                    Column {
                        totals.forEach { total ->
                            Text(
                                "${total.asset.code}: $sourceName ${formatAmount(
                                    total.source,
                                )} · selected ${formatAmount(total.selected)}" +
                                    " · difference ${formatAmount(total.difference)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (total.balanced) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (selected.isNotEmpty() && totals.any { !it.balanced }) {
                            Text(
                                "The totals differ. Match anyway if the two sides book fees differently.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList()) }, enabled = selected.isNotEmpty()) {
                Text("Match (${selected.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerLegLine(
    leg: ReconciliationLeg,
    accountNames: Map<AccountId, String>,
    delta: Duration? = null,
    enabled: Boolean = true,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        Text(leg.timestamp.displayDateTime(), style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.width(130.dp))
        if (delta != null) {
            Text(formatDelta(delta), style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.width(80.dp))
        }
        Text(
            accountNames[leg.accountId].orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.width(120.dp),
        )
        Text(formatAmount(leg.amount), style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.width(150.dp))
        Text(leg.description, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private val ReconciliationLeg.key: String get() = "$transactionId-${accountId.id}-${amount.asset.id}"
