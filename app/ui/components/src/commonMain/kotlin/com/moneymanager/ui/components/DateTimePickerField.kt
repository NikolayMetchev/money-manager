@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A date and a time picker side by side. [value] null shows both empty; picking either then fills the other
 * from [defaultTime] (so an end bound can default to the end of the day). With [onClear] a clear button
 * resets it to null; leave it null when a value is required. A pick outside [minValue, maxValue] (the other end's
 * bound, say) is not offered: earlier or later days are disabled, and a time is clamped into the range.
 */
@Composable
fun DateTimePickerField(
    value: LocalDateTime?,
    onValueChange: (LocalDateTime) -> Unit,
    dateLabel: String = "Date",
    timeLabel: String = "Time",
    enabled: Boolean = true,
    defaultTime: LocalTime = LocalTime(0, 0),
    minValue: LocalDateTime? = null,
    maxValue: LocalDateTime? = null,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val today =
        remember {
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        }
    val shownTime = value?.time ?: defaultTime

    fun clamp(picked: LocalDateTime): LocalDateTime {
        val atLeast = minValue?.let { maxOf(picked, it) } ?: picked
        return maxValue?.let { minOf(atLeast, it) } ?: atLeast
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerReadonlyField(
            value = value?.date?.toString().orEmpty(),
            label = dateLabel,
            icon = "📅",
            onIconClick = { showDatePicker = true },
            iconEnabled = enabled,
            modifier = Modifier.weight(1.5f),
        )
        PickerReadonlyField(
            value = value?.let { LocalTime(it.hour, it.minute).toString() }.orEmpty(),
            label = timeLabel,
            icon = "🕔",
            onIconClick = { showTimePicker = true },
            iconEnabled = enabled,
            modifier = Modifier.weight(1f),
        )
        if (onClear != null) {
            TextButton(onClick = onClear, enabled = enabled && value != null) { Text("Clear") }
        }
    }

    if (showDatePicker) {
        val datePickerState =
            rememberDatePickerState(
                selectableDates = dateBounds(minValue?.date, maxValue?.date),
                initialSelectedDateMillis =
                    clamp(
                        LocalDateTime(value?.date ?: today, LocalTime(0, 0)),
                    ).date.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                            // Keep the seconds of a value being edited, so changing the date can't alter them.
                            onValueChange(
                                clamp(
                                    LocalDateTime(
                                        date,
                                        LocalTime(shownTime.hour, shownTime.minute, shownTime.second, shownTime.nanosecond),
                                    ),
                                ),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState =
            rememberTimePickerState(initialHour = shownTime.hour, initialMinute = shownTime.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(
                            clamp(
                                LocalDateTime(
                                    value?.date ?: today,
                                    LocalTime(timePickerState.hour, timePickerState.minute, value?.second ?: 0, value?.nanosecond ?: 0),
                                ),
                            ),
                        )
                        showTimePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timePickerState) },
        )
    }
}

@Composable
private fun PickerReadonlyField(
    value: String,
    label: String,
    icon: String,
    onIconClick: () -> Unit,
    iconEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodySmall,
        trailingIcon = {
            IconButton(onClick = onIconClick, enabled = iconEnabled) {
                Text(text = icon, style = MaterialTheme.typography.bodyLarge)
            }
        },
        modifier = modifier,
        enabled = false,
        singleLine = true,
        colors =
            OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

/** Days outside [min, max] (either may be null) can't be picked. */
private fun dateBounds(
    min: LocalDate?,
    max: LocalDate?,
): SelectableDates =
    object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val date = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date
            return (min == null || date >= min) && (max == null || date <= max)
        }
    }
