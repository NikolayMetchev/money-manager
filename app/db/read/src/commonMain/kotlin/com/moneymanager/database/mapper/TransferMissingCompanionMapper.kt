package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferMissingCompanion
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object TransferMissingCompanionMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        timestamp: Long,
        description: String,
        amount: String,
        matchValue: String,
        sourceAccountId: Long,
        sourceAccountName: String,
        targetAccountId: Long,
        targetAccountName: String,
        assetId: Long,
        assetCode: String,
        assetName: String,
        assetScaleFactor: Long,
        assetKind: String,
    ): TransferMissingCompanion {
        val asset = AssetRowMapper.buildAsset(assetId, assetCode, assetName, assetScaleFactor, assetKind)
        return TransferMissingCompanion(
            transferId = TransferId(id),
            matchValue = matchValue,
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
