@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * A transfer matched by a companion transaction rule that has no companion transfer yet.
 *
 * Carries everything needed to display the match and create its mirror companion:
 * the companion flips [sourceAccountId]/[targetAccountId] and reuses [timestamp] and
 * the currency of [amount].
 *
 * @property matchValue The matched attribute value (e.g. "ACCRUAL_CHARGE-18326272")
 *                      that the companion's link attribute must reference
 * @property amount The matched transfer's amount, shown for context when entering
 *                  the companion amount
 */
data class TransferMissingCompanion(
    val transferId: TransferId,
    val matchValue: String,
    val timestamp: Instant,
    val description: String,
    val sourceAccountId: AccountId,
    val sourceAccountName: String,
    val targetAccountId: AccountId,
    val targetAccountName: String,
    val amount: Money,
)
