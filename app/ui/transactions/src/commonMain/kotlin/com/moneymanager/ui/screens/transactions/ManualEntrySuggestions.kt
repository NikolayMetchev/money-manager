package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.moneymanager.tasks.Progress
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

/**
 * Finds the suggestions for [placeholders] (trade and the code of its unnamed asset) and the fees of [withdrawals], reporting to
 * [onProgress] as it goes: it reads the sources' records around every one of them, which takes a while for a long list.
 */
@Suppress("LongParameterList")
internal suspend fun loadManualEntrySuggestions(
    placeholders: List<Pair<Trade, String>>,
    withdrawals: List<PendingManualEntry>,
    reconciliationRepository: ReconciliationReadRepository,
    accountRepository: AccountReadRepository,
    tradeRepository: TradeReadRepository,
    transactionRepository: TransactionReadRepository,
    onProgress: (Progress) -> Unit = {},
): ManualEntrySuggestions {
    if (placeholders.isEmpty() && withdrawals.isEmpty()) return ManualEntrySuggestions()
    val steps = placeholders.size + withdrawals.size + FIXED_STEPS
    var done = 0

    fun step(detail: String) {
        onProgress(Progress(detail = detail, fraction = done.toFloat() / steps, processed = done, total = steps))
        done++
    }
    step("Reading the reconciliation sources' wallets")
    val shadow = reconciliationRepository.getShadowAccounts().first()
    if (shadow.isEmpty()) return ManualEntrySuggestions()
    val shadowIds = shadow.mapTo(mutableSetOf()) { it.accountId }
    val shadowTrades =
        placeholders
            .flatMapIndexed { index, (trade, _) ->
                step("Looking for the sources' record of exchange ${index + 1} of ${placeholders.size}")
                tradeRepository.getTradesByAccountsAndDateRange(shadowIds, trade.timestamp - 1.seconds, trade.timestamp + 1.seconds)
            }.distinctBy { it.id }
    val slack = FEE_SUGGESTION_WINDOW + 5.seconds
    val sourceTransfers =
        withdrawals
            .flatMapIndexed { index, withdrawal ->
                step("Looking for the sources' record of withdrawal ${index + 1} of ${withdrawals.size}")
                transactionRepository.getTransactionsByDateRange(withdrawal.timestamp - slack, withdrawal.timestamp + slack).first()
            }.filter { it.sourceAccountId in shadowIds }
            .distinctBy { it.id }
    val feeAccountIds = shadow.filter { it.feeAccountFor != null }.mapTo(mutableSetOf()) { it.accountId }
    // A source fee a person already matched is spoken for; a wallet counts as the account it is linked to, or would be linked to.
    step("Checking which fees are already matched")
    val claimedFees =
        if (withdrawals.isEmpty()) {
            emptySet()
        } else {
            reconciliationRepository
                .getLegs(feeAccountIds)
                .filter { it.matchIds.isNotEmpty() }
                .mapTo(mutableSetOf()) { TransferId(it.transactionId) }
        }
    step("Checking which wallets are which accounts")
    val mirrored = mirroredAccounts(shadow, reconciliationRepository, accountRepository)
    val mirrors: (AccountId, AccountId) -> Boolean = { wallet, real -> (wallet to real) in mirrored }
    step("Matching")
    return ManualEntrySuggestions(
        trades = suggestOtherSides(placeholders, shadowTrades, mirrors),
        fees = suggestFees(withdrawals, sourceTransfers, feeAccountIds, claimedFees, mirrors),
        shadowAccounts = shadow.associateBy { it.accountId },
    )
}

/** Steps besides one per exchange and withdrawal: the wallets, the matched fees, the wallet-to-account check, and the matching. */
private const val FIXED_STEPS = 4

/** The bar and step shown while the suggestions are being found. */
@Composable
internal fun SuggestionProgress(progress: Progress) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            "Finding suggestions from the reconciliation sources: ${progress.detail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
