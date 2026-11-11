package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.WellKnownIds

/**
 * A person's answer for one placeholder trade ([original]): the real [amount] (in its own currency) of the leg whose asset
 * was [placeholderAssetCode], the asset nobody had named.
 */
data class ManualTradeInput(
    val original: Trade,
    val placeholderAssetCode: String,
    val amount: Money,
)

/**
 * Replaces each placeholder trade with the trade the person described: the same instant, accounts and known leg, with the
 * placeholder's leg now [ManualTradeInput.amount]. The placeholder is excluded in the same batch, so the two are never
 * both counted, and it leaves Manual Entries with the exclusion.
 */
suspend fun ImportEngine.createManualTradeEntries(inputs: List<ManualTradeInput>) {
    if (inputs.isEmpty()) return
    val trades =
        inputs.mapIndexed { index, (original, code, amount) ->
            val placeholderIsTo = original.to.asset.code == code
            ImportTradeIntent(
                key = LocalTradeKey("manual-trade-entry-$index"),
                source = Source.Manual,
                timestamp = original.timestamp,
                description = original.description,
                fromAccountId = original.fromAccountId,
                fromAmount = if (placeholderIsTo) original.from else amount,
                toAccountId = original.toAccountId,
                toAmount = if (placeholderIsTo) amount else original.to,
            )
        }
    import(
        ImportBatch(
            trades = trades,
            tradeAttributeMutations =
                inputs.map {
                    TradeAttributeMutation.Set(
                        it.original.id,
                        AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID),
                        "replaced by a manual entry",
                    )
                },
        ),
    )
}
