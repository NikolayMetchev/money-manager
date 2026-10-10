package com.moneymanager.ui.screens.transactions

import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.moneymanager.humanreadable.HumanReadable
import kotlin.time.Instant

internal val logger = Logger.withTag("Transactions")

internal val ACCOUNT_COLUMN_MIN_WIDTH = 100.dp

internal fun formatTimeDiff(
    oldTimestamp: Instant,
    newTimestamp: Instant,
): String {
    val duration = newTimestamp - oldTimestamp
    val sign = if (duration.isPositive()) "+" else "-"
    return "$sign${HumanReadable.duration(duration.absoluteValue)}"
}
