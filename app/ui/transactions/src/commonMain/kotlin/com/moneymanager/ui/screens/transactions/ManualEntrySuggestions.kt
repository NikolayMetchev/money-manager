package com.moneymanager.ui.screens.transactions

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.reconciliation.FEE_SUGGESTION_WINDOW
import com.moneymanager.reconciliation.FeeSuggestion
import com.moneymanager.reconciliation.OtherSideSuggestion
import com.moneymanager.reconciliation.planAutoLinks
import com.moneymanager.reconciliation.realAccounts
import com.moneymanager.reconciliation.suggestFees
import com.moneymanager.reconciliation.suggestOtherSides
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds

/**
 * What the reconciliation sources say about the entries Manual Entries is waiting on: the other side of each unnamed exchange
 * ([trades]), the fee of each withdrawal ([fees]), and the [shadowAccounts] (a source's wallets) those records sit on, so a
 * suggestion can say which source it is from.
 */
data class ManualEntrySuggestions(
    val trades: Map<TradeId, OtherSideSuggestion> = emptyMap(),
    val fees: Map<TransferId, FeeSuggestion> = emptyMap(),
    val shadowAccounts: Map<AccountId, ShadowAccount> = emptyMap(),
)

/**
 * Suggestions kept above the screen, like `ReconciliationCache`: finding them reads and matches the sources' records around
 * every pending entry, so coming back to Manual Entries shows them again instead of redoing that. They stay good until the
 * database is written to (`ImportEngine.writeVersion`) or the entries waiting change.
 */
class ManualEntrySuggestionCache {
    private class Entry(
        val waiting: Set<Long>,
        val writeVersion: Long,
        val suggestions: ManualEntrySuggestions,
    )

    private var entry: Entry? = null

    /** The suggestions for exactly [waiting] (the ids of what is pending), if nothing has been written since. */
    fun get(
        waiting: Set<Long>,
        writeVersion: Long,
    ): ManualEntrySuggestions? = entry?.takeIf { it.waiting == waiting && it.writeVersion == writeVersion }?.suggestions

    fun put(
        waiting: Set<Long>,
        writeVersion: Long,
        suggestions: ManualEntrySuggestions,
    ) {
        entry = Entry(waiting, writeVersion, suggestions)
    }

    /** The last suggestions, to show while fresh ones load, provided nothing has been written since they were found. */
    fun peek(writeVersion: Long): ManualEntrySuggestions? = entry?.takeIf { it.writeVersion == writeVersion }?.suggestions
}

/** Finds the suggestions for [placeholders] (trade and the code of its unnamed asset) and the fees of [withdrawals]. */
internal suspend fun loadManualEntrySuggestions(
    placeholders: List<Pair<Trade, String>>,
    withdrawals: List<PendingManualEntry>,
    reconciliationRepository: ReconciliationReadRepository,
    accountRepository: AccountReadRepository,
    tradeRepository: TradeReadRepository,
    transactionRepository: TransactionReadRepository,
): ManualEntrySuggestions {
    if (placeholders.isEmpty() && withdrawals.isEmpty()) return ManualEntrySuggestions()
    val shadow = reconciliationRepository.getShadowAccounts().first()
    if (shadow.isEmpty()) return ManualEntrySuggestions()
    val shadowIds = shadow.mapTo(mutableSetOf()) { it.accountId }
    val shadowTrades =
        placeholders
            .flatMap { (trade, _) ->
                tradeRepository.getTradesByAccountsAndDateRange(shadowIds, trade.timestamp - 1.seconds, trade.timestamp + 1.seconds)
            }.distinctBy { it.id }
    val slack = FEE_SUGGESTION_WINDOW + 5.seconds
    val sourceTransfers =
        withdrawals
            .flatMap { transactionRepository.getTransactionsByDateRange(it.timestamp - slack, it.timestamp + slack).first() }
            .filter { it.sourceAccountId in shadowIds }
            .distinctBy { it.id }
    val feeAccountIds = shadow.filter { it.feeAccountFor != null }.mapTo(mutableSetOf()) { it.accountId }
    // A source fee a person already matched is spoken for; a wallet counts as the account it is linked to, or would be linked to.
    val claimedFees =
        if (withdrawals.isEmpty()) {
            emptySet()
        } else {
            reconciliationRepository
                .getLegs(
                    feeAccountIds,
                ).filter { it.matchIds.isNotEmpty() }
                .mapTo(mutableSetOf()) { TransferId(it.transactionId) }
        }
    val mirrored = mirroredAccounts(shadow, reconciliationRepository, accountRepository)
    val mirrors: (AccountId, AccountId) -> Boolean = { wallet, real -> (wallet to real) in mirrored }
    return ManualEntrySuggestions(
        trades = suggestOtherSides(placeholders, shadowTrades, mirrors),
        fees = suggestFees(withdrawals, sourceTransfers, feeAccountIds, claimedFees, mirrors),
        shadowAccounts = shadow.associateBy { it.accountId },
    )
}

/** The (wallet, real account) pairs that are one account: those linked, and those [planAutoLinks] would link by name. */
private suspend fun mirroredAccounts(
    shadow: List<ShadowAccount>,
    reconciliationRepository: ReconciliationReadRepository,
    accountRepository: AccountReadRepository,
): Set<Pair<AccountId, AccountId>> {
    val links = reconciliationRepository.getLinks().first()
    val real = realAccounts(accountRepository.getAllAccounts().first(), shadow)
    val byName =
        reconciliationRepository
            .getSources()
            .first()
            .flatMap { source -> planAutoLinks(source, shadow, links, real).entries }
            .map { it.key to it.value }
    return links.mapTo(mutableSetOf()) { it.shadowAccountId to it.realAccountId } + byName
}
