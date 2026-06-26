package com.moneymanager.ui.screens.transactions

import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import nl.jacobras.humanreadable.HumanReadable
import org.lighthousegames.logging.logging
import kotlin.time.Instant

internal val logger = logging()

internal val ACCOUNT_COLUMN_MIN_WIDTH = 100.dp

internal fun resolveAccountName(
    accountId: AccountId,
    accounts: List<Account>,
): String = accounts.find { it.id == accountId }?.name ?: "#${accountId.id}"

internal fun formatTimeDiff(
    oldTimestamp: Instant,
    newTimestamp: Instant,
): String {
    val duration = newTimestamp - oldTimestamp
    val sign = if (duration.isPositive()) "+" else "-"
    return "$sign${HumanReadable.duration(duration.absoluteValue)}"
}
