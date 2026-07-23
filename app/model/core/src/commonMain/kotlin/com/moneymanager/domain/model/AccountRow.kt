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
    /**
     * When this row is the funding leg of a pass-through (conduit) charge (card → conduit), the id of its
     * linked spend leg (conduit → merchant). See `com.moneymanager.domain.model.passthrough.PassThroughAccount`.
     */
    val passThroughSpendId: TransferId? = null,
    /** When this row IS the spend leg of a pass-through charge, the id of its funding leg. */
    val passThroughFundingId: TransferId? = null,
    /** When this row is a trade filling an exchange order, the id of that order. */
    val exchangeOrderId: ExchangeOrderId? = null,
    val kind: TransactionKind = TransactionKind.TRANSFER,
)
