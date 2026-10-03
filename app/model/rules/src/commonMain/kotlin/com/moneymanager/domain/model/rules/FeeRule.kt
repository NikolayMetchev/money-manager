package com.moneymanager.domain.model.rules

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A fee a row or item reports alongside its movement, booked as its own movement linked to it — the one
 * fee vocabulary for CSV amount mappings, API transactions and API trades.
 *
 * @property amount The fee's amount. Its sign is ignored except on an API transfer, where a negative fee
 *   is a refund of an earlier charge. Zero or blank means no fee.
 * @property currency The fee's asset code; null (or blank) means the movement's own asset — for a trade,
 *   its quote asset.
 * @property description The fee movement's description; null (or blank) uses a generic one.
 * @property conditions All must hold for the row to carry a fee at all (empty = always).
 * @property includedInAmount Whether the movement's amount is gross, already including the fee: the fee
 *   is then carved out of it, so the two sum back to the reported amount (Monzo's ATM withdrawals).
 *   Otherwise the fee is a movement on top of a net amount.
 * @property chargedOnAsset When set, the fee is booked only on the row whose asset is this value — for a
 *   ledger that repeats one fill's fee on every leg, charged in the pair's quote asset (Coinbase).
 *
 * A trade's fee is a field of the trade itself, so a trade mapping uses only [amount], [currency] and
 * [conditions].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FeeRule(
    val amount: ValueExpr,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val currency: ValueExpr? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: ValueExpr? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedConditionListSerializer::class)
    val conditions: List<Condition> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val includedInAmount: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val chargedOnAsset: ValueExpr? = null,
)
