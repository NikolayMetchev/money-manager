package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * A transfer a manual-entry rule asked for an amount on, which nobody has entered yet.
 *
 * Carries what the screen shows and what the entry is built from: the entry reuses [timestamp] and the asset of
 * [amount], and runs between the transfer's accounts (reversed, or from [sourceAccountId] to a named account).
 */
data class PendingManualEntry(
    val transferId: TransferId,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val sourceAccountName: String,
    val targetAccountId: AccountId,
    val targetAccountName: String,
    val amount: Money,
)
