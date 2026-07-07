@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object TransferMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        revisionId: Long,
        timestamp: Long,
        description: String,
        sourceAccountId: Long,
        targetAccountId: Long,
        amount: String,
        assetId: Long,
        assetCode: String,
        assetName: String,
        assetScaleFactor: Long,
        assetKind: String,
    ): Transfer {
        val asset = AssetRowMapper.buildAsset(assetId, assetCode, assetName, assetScaleFactor, assetKind)
        return Transfer(
            id = TransferId(id),
            revisionId = revisionId,
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            sourceAccountId = AccountId(sourceAccountId),
            targetAccountId = AccountId(targetAccountId),
            amount = Money(BigInteger(amount), asset),
            attributes = emptyList(),
        )
    }
}
