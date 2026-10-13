package com.moneymanager.query

import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The JSON shape of an [AccountWithBalances]. Domain entities stay unserializable; this is the published form. */
@Serializable
data class AccountJson(
    val id: Long,
    val name: String,
    /** ISO-8601 instant. */
    val openingDate: String,
    val categoryId: Long,
    val shadow: Boolean,
    val owners: List<OwnerJson>,
    val balances: List<BalanceJson>,
)

@Serializable
data class OwnerJson(
    val id: Long,
    val name: String,
)

@Serializable
data class BalanceJson(
    /** The asset's code, e.g. `GBP` or `BTC`. */
    val asset: String,
    val kind: AssetKind,
    /** An exact decimal string with no trailing zeros, e.g. `-12.5`; never a JSON number, which would round. */
    val amount: String,
)

@Serializable
enum class AssetKind {
    @SerialName("currency")
    CURRENCY,

    @SerialName("crypto")
    CRYPTO,
}

fun AccountWithBalances.toJson(): AccountJson =
    AccountJson(
        id = account.id.id,
        name = account.name,
        openingDate = account.openingDate.toString(),
        categoryId = account.categoryId,
        shadow = isShadow,
        owners = owners.map { it.toOwnerJson() },
        balances = balances.map { it.toBalanceJson() },
    )

fun Person.toOwnerJson(): OwnerJson = OwnerJson(id = id.id, name = fullName)

fun Money.toBalanceJson(): BalanceJson =
    BalanceJson(
        asset = asset.code,
        kind =
            when (asset) {
                is Currency -> AssetKind.CURRENCY
                is CryptoAsset -> AssetKind.CRYPTO
            },
        amount = toDisplayValue().toString(),
    )
