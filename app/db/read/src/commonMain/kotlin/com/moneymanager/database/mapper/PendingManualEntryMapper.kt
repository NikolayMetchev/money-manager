package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object PendingManualEntryMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        timestamp: Long,
        description: String,
        amount: String,
        sourceAccountId: Long,
        sourceAccountName: String,
        targetAccountId: Long,
        targetAccountName: String,
        assetId: Long,
        assetCode: String,
        assetName: String,
        assetScaleFactor: Long,
        assetFractionDigits: Long?,
        assetKind: String,
    ): PendingManualEntry {
        val asset = AssetRowMapper.buildAsset(assetId, assetCode, assetName, assetScaleFactor, assetFractionDigits, assetKind)
        return PendingManualEntry(
            transferId = TransferId(id),
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            sourceAccountId = AccountId(sourceAccountId),
            sourceAccountName = sourceAccountName,
            targetAccountId = AccountId(targetAccountId),
            targetAccountName = targetAccountName,
            amount = Money(BigInteger(amount), asset),
        )
    }
}
