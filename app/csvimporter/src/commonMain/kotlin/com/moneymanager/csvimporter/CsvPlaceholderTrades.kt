package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.importengineapi.createCurrency
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** How far a placeholder's instant may sit from the leg it stands in for. */
private val PLACEHOLDER_MATCH_WINDOW = 2.seconds

/** What Manual Entries shows beside a placeholder trade. */
private const val PLACEHOLDER_ENTRY_PROMPT = "enter the currency and amount of the other side"

/** The asset code the leg's rule books an unpaired leg against, or null when the rule makes it wait instead. */
fun CsvImportStrategy.unpairedAssetOf(leg: CsvTransferWithAttributes): String? =
    (config.legGroups.getOrNull(leg.groupLeg?.ruleIndex ?: return null)?.assembly as? LegAssembly.Trade)?.unpairedAsset

/**
 * One trade per unpaired leg in [legs], between the leg's asset and the rule's [unpairedAssetOf] asset: the same number
 * on both sides, as a placeholder for what the file does not say. Each is tagged `manual-entry`, so Manual Entries asks a
 * person for the real currency and amount, and is replaced by the real trade should the other side turn up
 * ([supersededPlaceholders]).
 */
suspend fun unpairedLegTrades(
    legs: List<CsvTransferWithAttributes>,
    strategy: CsvImportStrategy,
    csvImportId: CsvImportId,
    importEngine: ImportEngine,
    manualEntryTypeId: AttributeTypeId,
): List<ImportTradeIntent> {
    if (legs.isEmpty()) return emptyList()
    val placeholderAssets =
        legs
            .mapNotNull { strategy.unpairedAssetOf(it) }
            .distinct()
            .associateWith { code ->
                val name = "$code (not yet named)"
                Currency(
                    id = importEngine.createCurrency(code, name, null),
                    code = code,
                    name = name,
                    scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
                )
            }
    return legs.mapNotNull { leg ->
        val placeholder = placeholderAssets[strategy.unpairedAssetOf(leg)] ?: return@mapNotNull null
        val debit = leg.groupLeg?.side == GroupLegSide.DEBIT
        val known = leg.transfer.amount
        val unknown = Money(known.amount, placeholder)
        val owner = if (debit) leg.transfer.sourceAccountId else leg.transfer.targetAccountId
        ImportTradeIntent(
            key = LocalTradeKey("$CSV_TRADE_KEY_PREFIX${csvImportId.id}-${leg.rowIndex}"),
            source = Source.Csv(csvImportId),
            timestamp = leg.transfer.timestamp,
            description = leg.transfer.description,
            fromAccountId = owner,
            fromAmount = if (debit) known else unknown,
            toAccountId = owner,
            toAmount = if (debit) unknown else known,
            attributes = listOf(NewAttribute(manualEntryTypeId, PLACEHOLDER_ENTRY_PROMPT)),
        )
    }
}

/**
 * The exclusions that retire placeholder trades the [assembled] trades now replace: the real trade is booked, so the
 * placeholder for any of its legs (same account, asset, amount and instant) must stop counting. Empty when the strategy
 * books no placeholders or there is no [tradeRepository] to find them.
 */
suspend fun supersededPlaceholders(
    assembled: List<AssembledTrade>,
    strategy: CsvImportStrategy,
    tradeRepository: TradeReadRepository?,
    exclusionTypeId: AttributeTypeId,
): List<TradeAttributeMutation> {
    val codes =
        strategy.config.legGroups
            .mapNotNull { (it.assembly as? LegAssembly.Trade)?.unpairedAsset }
            .toSet()
    if (tradeRepository == null || codes.isEmpty() || assembled.isEmpty()) return emptyList()
    val pending =
        codes
            .flatMap { tradeRepository.getPendingManualTrades(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME, it).first() }
            .distinctBy { it.id }
    if (pending.isEmpty()) return emptyList()
    val claimed = mutableSetOf<Trade>()
    return assembled.flatMap { trade ->
        trade.group.rows.mapNotNull { leg ->
            val placeholder = pending.firstOrNull { it !in claimed && it.standsInFor(leg) } ?: return@mapNotNull null
            claimed += placeholder
            TradeAttributeMutation.Set(placeholder.id, exclusionTypeId, "replaced by the trade it stood in for: ${trade.description}")
        }
    }
}

private fun Trade.standsInFor(leg: CsvTransferWithAttributes): Boolean {
    val moved = leg.transfer.amount
    val sameInstant = abs((timestamp - leg.transfer.timestamp).inWholeMilliseconds) <= PLACEHOLDER_MATCH_WINDOW.inWholeMilliseconds
    val sameLeg =
        if (leg.groupLeg?.side == GroupLegSide.DEBIT) {
            fromAccountId == leg.transfer.sourceAccountId && from.asset.id == moved.asset.id && from.amount == moved.amount
        } else {
            toAccountId == leg.transfer.targetAccountId && to.asset.id == moved.asset.id && to.amount == moved.amount
        }
    return sameInstant && sameLeg
}
