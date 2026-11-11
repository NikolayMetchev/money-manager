package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.WellKnownIds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A person's answer for one placeholder trade ([original]): the real [amount] (in its own currency) of the leg whose asset
 * was [placeholderAssetCode], the asset nobody had named.
 */
data class ManualTradeInput(
    val original: Trade,
    val placeholderAssetCode: String,
    val amount: Money,
) {
    /** The leg that is not the placeholder: the side of the exchange the statement did report. */
    private val known: Money get() = if (original.to.asset.code == placeholderAssetCode) original.from else original.to

    /**
     * Why [amount] cannot be this exchange's other side, or null when it can: an exchange has two different assets, so it
     * is neither the placeholder itself (which would leave the entry exactly as it was) nor the one already known.
     */
    fun problem(): String? =
        when (amount.asset.code) {
            placeholderAssetCode -> "$placeholderAssetCode is the placeholder, choose the real currency"
            known.asset.code -> "The other side of an exchange cannot be ${known.asset.code} again"
            else -> null
        }
}

/**
 * Replaces each placeholder trade with the trade the person described: the same instant, accounts and known leg, with the
 * placeholder's leg now [ManualTradeInput.amount]. The placeholder is excluded once the replacement exists, so the two are
 * never both counted, and it leaves Manual Entries with the exclusion. The replacement keeps the `manual-entry` tag, so the
 * statement's own record of the trade, should it turn up, retires it.
 *
 * If excluding fails the replacements are deleted again, leaving the placeholder pending to try once more.
 */
suspend fun ImportEngine.createManualTradeEntries(inputs: List<ManualTradeInput>) {
    if (inputs.isEmpty()) return
    inputs.forEach { require(it.problem() == null) { checkNotNull(it.problem()) } }
    val entryTypeId = getOrCreateAttributeType(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME)
    val keys = inputs.indices.map { LocalTradeKey("manual-trade-entry-$it") }
    val created =
        import(
            ImportBatch(
                trades =
                    inputs.mapIndexed { index, input ->
                        val original = input.original
                        val placeholderIsTo = original.to.asset.code == input.placeholderAssetCode
                        ImportTradeIntent(
                            key = keys[index],
                            source = Source.Manual,
                            timestamp = original.timestamp,
                            description = original.description,
                            fromAccountId = original.fromAccountId,
                            fromAmount = if (placeholderIsTo) original.from else input.amount,
                            toAccountId = original.toAccountId,
                            toAmount = if (placeholderIsTo) input.amount else original.to,
                            attributes = listOf(NewAttribute(entryTypeId, "entered by hand")),
                        )
                    },
            ),
        )
    var excluded = false
    try {
        import(
            ImportBatch(
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
        excluded = true
    } finally {
        if (!excluded) {
            withContext(NonCancellable) {
                import(
                    ImportBatch(
                        trades =
                            keys.mapNotNull { created.createdTradeIds[it] }.map {
                                ImportTradeIntent(
                                    key = LocalTradeKey("manual-trade-entry-undo-${it.id}"),
                                    source = Source.Manual,
                                    operation = ImportOperation.DELETE,
                                    existingId = it,
                                )
                            },
                    ),
                )
            }
        }
    }
}
