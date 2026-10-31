package com.moneymanager.ui.components.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.rules.SettlementWindow

/**
 * Edits one reconcile window: clock time in seconds, or business days on the locations of the accounts a match
 * involves. The one editor for every reconcile window, CSV and API alike.
 */
@Composable
fun SettlementWindowEditor(
    window: SettlementWindow,
    onWindowChange: (SettlementWindow) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = window is SettlementWindow.Elapsed,
                onClick = {
                    if (window !is SettlementWindow.Elapsed) {
                        onWindowChange(
                            SettlementWindow.Elapsed(window.approximateSeconds()),
                        )
                    }
                },
                label = { Text("Clock time") },
                enabled = enabled,
            )
            FilterChip(
                selected = window is SettlementWindow.BusinessDays,
                onClick = {
                    if (window !is SettlementWindow.BusinessDays) {
                        onWindowChange(
                            SettlementWindow.BusinessDays((window.approximateSeconds() / SECONDS_PER_DAY).toInt().coerceAtLeast(1)),
                        )
                    }
                },
                label = { Text("Business days") },
                enabled = enabled,
            )
        }
        val amount =
            when (window) {
                is SettlementWindow.Elapsed -> window.seconds
                is SettlementWindow.BusinessDays -> window.days.toLong()
            }
        // Keyed on the window so a switch of kind resyncs the buffer, while invalid input in between is kept.
        var text by remember(window) { mutableStateOf(amount.toString()) }
        val parsed = text.trim().toLongOrNull()?.takeIf { it >= 0 }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                input.trim().toLongOrNull()?.takeIf { it >= 0 }?.let { value ->
                    onWindowChange(
                        when (window) {
                            is SettlementWindow.Elapsed -> SettlementWindow.Elapsed(value)
                            is SettlementWindow.BusinessDays -> SettlementWindow.BusinessDays(value.toInt())
                        },
                    )
                }
            },
            label = { Text(if (window is SettlementWindow.Elapsed) "Seconds" else "Business days") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = parsed == null,
            supportingText = { Text(window.summary()) },
        )
    }
}

/** The window in words, e.g. "within 2 days" or "within 3 business days of the accounts' locations". */
fun SettlementWindow.summary(): String =
    when (this) {
        is SettlementWindow.Elapsed -> "within ${formatSeconds(seconds)}"
        is SettlementWindow.BusinessDays -> "within $days business day${if (days == 1) "" else "s"} of the accounts' locations"
    }

private fun SettlementWindow.approximateSeconds(): Long =
    when (this) {
        is SettlementWindow.Elapsed -> seconds
        is SettlementWindow.BusinessDays -> days * SECONDS_PER_DAY
    }

private fun formatSeconds(seconds: Long): String =
    when {
        seconds != 0L && seconds % SECONDS_PER_DAY == 0L -> plural(seconds / SECONDS_PER_DAY, "day")
        seconds != 0L && seconds % SECONDS_PER_HOUR == 0L -> plural(seconds / SECONDS_PER_HOUR, "hour")
        seconds != 0L && seconds % SECONDS_PER_MINUTE == 0L -> plural(seconds / SECONDS_PER_MINUTE, "minute")
        else -> plural(seconds, "second")
    }

private fun plural(
    count: Long,
    unit: String,
): String = "$count $unit${if (count == 1L) "" else "s"}"

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
