package com.moneymanager.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualEntryInput
import com.moneymanager.importengineapi.createManualEntries
import com.moneymanager.importengineapi.createReconciliationMatches
import com.moneymanager.importengineapi.deleteManualEntries
import com.moneymanager.importengineapi.setReconciliationLinks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How far a source may stamp a withdrawal from the exchange's own record of it (a source uses the on-chain time). */
val FEE_SUGGESTION_WINDOW: Duration = 10.minutes

/** How far a source's fee may sit from the net transfer it was charged on: it stamps the two together. */
private val SAME_MOVEMENT = 5.seconds

/**
 * What a reconciliation source's own record says the fee of a withdrawal was: [fee], the source's [feeTransfer] charged on
 * the movement whose net amount, [netTransfer], plus the fee is exactly the withdrawal's gross amount.
 */
data class FeeSuggestion(
    val pending: PendingManualEntry,
    val fee: Money,
    val feeTransfer: Transfer,
    val netTransfer: Transfer,
)

/**
 * The fee of each withdrawal in [pending], read off [sourceTransfers] (transfers on a source's shadow accounts around them):
 * a transfer from a source wallet to one of its fee accounts ([feeAccountIds]) in the withdrawal's asset, within
 * [FEE_SUGGESTION_WINDOW] of it, for which the same wallet sent, within seconds, a net transfer such that net plus fee is
 * exactly the withdrawal's amount. Requiring that sum means a coincidental fee of the same asset is never taken, and
 * [mirrors] that the wallet is the account the withdrawal was made from (linked to it, or named for it), so another
 * exchange's withdrawal of the same size is never taken either. A fee in [claimedFeeTransfers] is already matched to another
 * fee. Nothing is suggested when two such pairs fit one withdrawal, or one pair fits two.
 */
fun suggestFees(
    pending: List<PendingManualEntry>,
    sourceTransfers: List<Transfer>,
    feeAccountIds: Set<AccountId>,
    claimedFeeTransfers: Set<TransferId> = emptySet(),
    mirrors: (wallet: AccountId, real: AccountId) -> Boolean = { _, _ -> true },
): Map<TransferId, FeeSuggestion> {
    // A fee a person already matched to a real fee is spoken for.
    val fees = sourceTransfers.filter { it.targetAccountId in feeAccountIds && it.id !in claimedFeeTransfers }
    val nets = sourceTransfers.filter { it.targetAccountId !in feeAccountIds }
    val fits =
        pending.associateWith { withdrawal ->
            fees.flatMap { fee ->
                nets
                    .filter { net ->
                        net.sourceAccountId == fee.sourceAccountId &&
                            mirrors(net.sourceAccountId, withdrawal.sourceAccountId) &&
                            net.amount.asset.id == withdrawal.amount.asset.id &&
                            fee.amount.asset.id == withdrawal.amount.asset.id &&
                            net.amount.amount + fee.amount.amount == withdrawal.amount.amount &&
                            (net.timestamp - fee.timestamp).absoluteValue <= SAME_MOVEMENT &&
                            (fee.timestamp - withdrawal.timestamp).absoluteValue <= FEE_SUGGESTION_WINDOW
                    }.map { net -> FeeSuggestion(withdrawal, fee.amount, fee, net) }
            }
        }
    val claims =
        fits.values
            .flatten()
            .groupingBy { it.feeTransfer.id }
            .eachCount()
    return fits.values
        .mapNotNull { suggestions -> suggestions.singleOrNull()?.takeIf { claims.getValue(it.feeTransfer.id) == 1 } }
        .associateBy { it.pending.transferId }
}

/**
 * Takes [suggestions]: books each fee as the entry the rule asks for ([description], [relationshipType] and
 * [excludedFromBalances] as for a typed entry) and stores the match between it and the source's own fee transfer. [links]
 * (shadow wallet → the real account it mirrors, from [planAutoLinks]) are made at the same time.
 */
suspend fun ImportEngine.acceptFeeSuggestions(
    suggestions: List<FeeSuggestion>,
    description: String,
    relationshipType: String,
    excludedFromBalances: Boolean,
    links: Map<AccountId, AccountId>,
) {
    if (suggestions.isEmpty()) return
    val entries =
        createManualEntries(
            inputs = suggestions.map { ManualEntryInput(it.pending, it.fee) },
            description = description,
            relationshipType = relationshipType,
            excludedFromBalances = excludedFromBalances,
        )
    // The matches are a second write: were it to fail, the entries would leave Manual Entries with nothing to show for it.
    try {
        createReconciliationMatches(suggestions.mapIndexed { index, suggestion -> listOf(entries[index], suggestion.feeTransfer.id) })
    } catch (failure: Exception) {
        withContext(NonCancellable) { deleteManualEntries(entries) }
        throw failure
    }
    links.forEach { (wallet, real) -> setReconciliationLinks(wallet, setOf(real)) }
}
