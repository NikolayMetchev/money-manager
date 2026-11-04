package com.moneymanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * From / To date and time pickers, each clearable and each usable alone. The range is in the system time zone;
 * an unpicked time of day is the start of the day for [from] and the end of it for [to]. [earliest] and [latest],
 * when known, bound what can be picked.
 */
@Composable
fun DateRangeFilterFields(
    from: Instant?,
    to: Instant?,
    onFromChange: (Instant?) -> Unit,
    onToChange: (Instant?) -> Unit,
    fromLabel: String,
    toLabel: String,
    earliest: Instant? = null,
    latest: Instant? = null,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val earliestLocal = remember(earliest) { earliest?.toLocalDateTime(zone) }
    val latestLocal = remember(latest) { latest?.toLocalDateTime(zone) }
    val fromLocal = from?.toLocalDateTime(zone)
    val toLocal = to?.toLocalDateTime(zone)
    DateTimePickerField(
        value = fromLocal,
        onValueChange = { onFromChange(it.toInstant(zone)) },
        minValue = earliestLocal,
        maxValue = toLocal ?: latestLocal,
        dateLabel = fromLabel,
        onClear = { onFromChange(null) },
    )
    DateTimePickerField(
        value = toLocal,
        onValueChange = { onToChange(it.toInstant(zone)) },
        minValue = fromLocal ?: earliestLocal,
        maxValue = latestLocal,
        dateLabel = toLabel,
        defaultTime = LocalTime(23, 59, 59, 999_999_999),
        onClear = { onToChange(null) },
    )
}
