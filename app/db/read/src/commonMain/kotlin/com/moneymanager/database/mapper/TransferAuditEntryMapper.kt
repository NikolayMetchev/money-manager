package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.audit.SelectAuditHistoryForTransfer
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferAuditEntry
import tech.mappie.api.ObjectMappie

object TransferAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForTransfer, TransferAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForTransfer): TransferAuditEntry =
        mapping {
            TransferAuditEntry::amount fromValue Money(BigInteger(from.amount), from.toAsset())
        }
}

private fun SelectAuditHistoryForTransfer.toAsset() =
    AssetRowMapper.buildAsset(
        id = asset_id,
        code = asset_code,
        name = asset_name,
        scaleFactor = asset_scale_factor,
        kind = asset_kind,
    )
