package com.moneymanager.ui.screens.transactions

import co.touchlab.kermit.Logger
import com.moneymanager.humanreadable.HumanReadable
import kotlin.time.Instant

internal val logger = Logger.withTag("Transactions")

internal fun formatTimeDiff(
    oldTimestamp: Instant,
    newTimestamp: Instant,
): String {
    val duration = newTimestamp - oldTimestamp
    val sign = if (duration.isPositive()) "+" else "-"
    return "$sign${HumanReadable.duration(duration.absoluteValue)}"
}
