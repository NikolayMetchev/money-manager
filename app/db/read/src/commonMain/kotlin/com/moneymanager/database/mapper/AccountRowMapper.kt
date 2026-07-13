@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object AccountRowMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        timestamp: Long,
        description: String,
        accountId: Long,
        transactionAmount: String,
        runningBalance: String,
        assetId: Long,
        assetCode: String,
        assetName: String,
        assetScaleFactor: Long,
        assetKind: String,
        // Nullable because the running-balance row LEFT JOINs both transfer and trade and COALESCEs the
        // accounts; every row is one or the other, so exactly one side is non-null.
        sourceAccountId: Long?,
        targetAccountId: Long?,
        isExcluded: Long,
        isReconciled: Long,
        feeTransferId: Long?,
        feeParentTransferId: Long?,
        passThroughSpendId: Long?,
        passThroughFundingId: Long?,
        exchangeOrderId: Long?,
        transactionKind: String,
    ): AccountRow {
        val asset = AssetRowMapper.buildAsset(assetId, assetCode, assetName, assetScaleFactor, assetKind)
        return AccountRow(
            transactionId = TransferId(id),
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            accountId = AccountId(accountId),
            transactionAmount = Money(BigInteger(transactionAmount), asset),
            runningBalance = Money(BigInteger(runningBalance), asset),
            sourceAccountId = AccountId(requireNotNull(sourceAccountId)),
            targetAccountId = AccountId(requireNotNull(targetAccountId)),
            isExcluded = isExcluded != 0L,
            isReconciled = isReconciled != 0L,
            feeTransferId = feeTransferId?.let { TransferId(it) },
            feeParentTransferId = feeParentTransferId?.let { TransferId(it) },
            passThroughSpendId = passThroughSpendId?.let { TransferId(it) },
            passThroughFundingId = passThroughFundingId?.let { TransferId(it) },
            exchangeOrderId = exchangeOrderId?.let { ExchangeOrderId(it) },
            kind = TransactionKind.valueOf(transactionKind),
        )
    }
}
