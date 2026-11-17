package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.presenter.transactions.ObservationsEvent
import com.moneymanager.presenter.transactions.ObservationsModel
import com.moneymanager.query.DetailObservation
import com.moneymanager.query.DetailSplit
import com.moneymanager.ui.util.formatAmount
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Who observed this transaction (each owner and slot, with what it says), what a person pinned, kept apart and set
 * on it, and the "same" and "different" decisions that change it.
 */
@Composable
fun ObservationsCard(
    model: ObservationsModel,
    accounts: List<Account>,
    modifier: Modifier = Modifier,
) {
    val observations = model.observations
    Card(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Observations", style = MaterialTheme.typography.titleMedium)
            when {
                observations == null && model.loading -> {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }

                observations == null || observations.observations.isEmpty() -> {
                    Text("No source observes this transaction.", style = MaterialTheme.typography.bodySmall)
                }

                else -> {
                    val all = observations.observations
                    all.forEach { observation ->
                        ObservationRow(
                            observation = observation,
                            accounts = accounts,
                            enabled = !model.applying,
                            // Only one observation: there is nothing for it to be different from.
                            others = all.filter { it.id != observation.id },
                            onEvent = model.eventSink,
                        )
                    }
                }
            }
            if (!observations?.splits.isNullOrEmpty()) {
                Text("Kept apart", style = MaterialTheme.typography.titleSmall)
                observations.splits.forEach { split -> SplitRow(split, enabled = !model.applying, onEvent = model.eventSink) }
            }
            if (!observations?.overrides.isNullOrEmpty()) {
                Text("Set by hand (wins over every observation)", style = MaterialTheme.typography.titleSmall)
                observations.overrides.forEach { override ->
                    Text(
                        "${override.name}: ${override.value ?: "removed"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ObservationRow(
    observation: DetailObservation,
    accounts: List<Account>,
    enabled: Boolean,
    others: List<DetailObservation>,
    onEvent: (ObservationsEvent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            val pinned = if (observation.pinned) " · pinned here" else ""
            Text(
                "${observation.ownerLabel} · ${observation.slot}$pinned",
                style = MaterialTheme.typography.bodyMedium,
            )
            val local = observation.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            val legs =
                observation.legs.joinToString("; ") {
                    "${accounts.nameOf(it.from)} → ${accounts.nameOf(it.to)} ${formatAmount(it.amount)}"
                }
            Text("$local · ${observation.description} · $legs", style = MaterialTheme.typography.bodySmall)
        }
        if (observation.pinned) {
            TextButton(onClick = { onEvent(ObservationsEvent.Unpin(observation.id)) }, enabled = enabled) { Text("Unpin") }
        } else {
            TextButton(onClick = { onEvent(ObservationsEvent.Pin(observation.id)) }, enabled = enabled) { Text("Same") }
        }
        if (others.isNotEmpty()) {
            TextButton(
                onClick = { onEvent(ObservationsEvent.Different(observation.id, others.map { it.id })) },
                enabled = enabled,
            ) { Text("Different") }
        }
    }
}

@Composable
private fun SplitRow(
    split: DetailSplit,
    enabled: Boolean,
    onEvent: (ObservationsEvent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val where = split.otherTransaction?.let { "on transaction $it" } ?: "no longer observed"
        Text(
            "Observation ${split.observation} is not ${split.other} ($where)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (split.otherTransaction != null) {
            TextButton(onClick = { onEvent(ObservationsEvent.Same(split.observation, split.other)) }, enabled = enabled) {
                Text("Same after all")
            }
        }
        TextButton(onClick = { onEvent(ObservationsEvent.Undo(split.observation, split.other)) }, enabled = enabled) {
            Text("Let the rules decide")
        }
    }
}

private fun List<Account>.nameOf(id: AccountId): String = firstOrNull { it.id == id }?.name ?: "#${id.id}"
