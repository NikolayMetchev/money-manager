package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object TradeMapper {
    @Suppress("LongParameterList")
    fun mapRaw(
        id: Long,
        revisionId: Long,
        timestamp: Long,
        description: String,
        fromAccountId: Long,
        fromAssetId: Long,
        fromAmount: String,
        fromAssetCode: String,
        fromAssetName: String,
        fromAssetScaleFactor: Long,
        fromAssetKind: String,
        toAccountId: Long,
        toAssetId: Long,
        toAmount: String,
        toAssetCode: String,
        toAssetName: String,
        toAssetScaleFactor: Long,
        toAssetKind: String,
    ): Trade {
        val fromAsset = AssetRowMapper.buildAsset(fromAssetId, fromAssetCode, fromAssetName, fromAssetScaleFactor, fromAssetKind)
        val toAsset = AssetRowMapper.buildAsset(toAssetId, toAssetCode, toAssetName, toAssetScaleFactor, toAssetKind)
        return Trade(
            id = TradeId(id),
            revisionId = revisionId,
            timestamp = fromEpochMilliseconds(timestamp),
            description = description,
            fromAccountId = AccountId(fromAccountId),
            from = Money(BigInteger(fromAmount), fromAsset),
            toAccountId = AccountId(toAccountId),
            to = Money(BigInteger(toAmount), toAsset),
        )
    }
}
