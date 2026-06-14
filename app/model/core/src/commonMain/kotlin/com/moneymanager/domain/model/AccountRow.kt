@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

data class AccountRow(
    val transactionId: TransactionId,
    val timestamp: Instant,
    val description: String,
    val accountId: AccountId,
    val transactionAmount: Money,
    val runningBalance: Money,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId,
    val isExcluded: Boolean = false,
    val isReconciled: Boolean = false,
    /** When this row is the main transaction of a fee link, the id of its linked fee transfer. */
    val feeTransferId: TransferId? = null,
    /** When this row IS a fee transfer, the id of the main transaction it belongs to. */
    val feeParentTransferId: TransferId? = null,
)
