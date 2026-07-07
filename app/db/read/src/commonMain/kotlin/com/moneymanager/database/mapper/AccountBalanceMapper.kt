package com.moneymanager.database.mapper

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.transfer.SelectAllBalances
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.Money
import tech.mappie.api.ObjectMappie

object AccountBalanceMapper : ObjectMappie<SelectAllBalances, AccountBalance>(), IdConversions {
    override fun map(from: SelectAllBalances): AccountBalance =
        mapping {
            AccountBalance::balance fromValue Money(BigInteger(from.balance), from.toAsset())
        }
}

private fun SelectAllBalances.toAsset() =
    AssetRowMapper.buildAsset(
        id = asset_id,
        code = asset_code,
        name = asset_name,
        scaleFactor = asset_scale_factor,
        kind = asset_kind,
    )
