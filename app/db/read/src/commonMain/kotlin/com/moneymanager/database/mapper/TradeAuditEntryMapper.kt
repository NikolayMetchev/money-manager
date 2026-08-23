package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.audit.SelectAuditHistoryForTrade
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TradeAuditEntry
import tech.mappie.api.ObjectMappie

object TradeAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForTrade, TradeAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForTrade): TradeAuditEntry =
        mapping {
            TradeAuditEntry::fromAmount fromValue Money(BigInteger(from.from_amount), from.fromAsset())
            TradeAuditEntry::toAmount fromValue Money(BigInteger(from.to_amount), from.toAsset())
        }
}

private fun SelectAuditHistoryForTrade.fromAsset() =
    AssetRowMapper.buildAsset(
        id = from_asset_id,
        code = from_asset_code,
        name = from_asset_name,
        scaleFactor = from_asset_scale_factor,
        kind = from_asset_kind,
    )

private fun SelectAuditHistoryForTrade.toAsset() =
    AssetRowMapper.buildAsset(
        id = to_asset_id,
        code = to_asset_code,
        name = to_asset_name,
        scaleFactor = to_asset_scale_factor,
        kind = to_asset_kind,
    )
