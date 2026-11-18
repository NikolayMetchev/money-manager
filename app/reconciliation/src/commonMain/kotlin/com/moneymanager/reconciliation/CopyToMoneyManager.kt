package com.moneymanager.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.importengineapi.CopyEnd
import com.moneymanager.importengineapi.CopyEnds
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.feeAccounts
import kotlin.time.Instant

/** A source trade as its copy books it; [copies] is the source's own (shadow) trade. */
data class CopiedTrade(
    val copies: TransactionId,
    val timestamp: Instant,
    val description: String,
    val account: AccountId,
    val from: Money,
    val to: Money,
)

/** A source transfer as its copy books it; [copies] is the source's own (shadow) transfer. */
data class CopiedTransfer(
    val copies: TransactionId,
    val timestamp: Instant,
    val description: String,
    val from: CopyEnd,
    val to: CopyEnd,
    val amount: Money,
)

/** What copying a source movement onto the real accounts would book, or why it can't be. */
sealed interface CopyPlan {
    data class Ready(
        val trades: List<CopiedTrade>,
        val transfers: List<CopiedTransfer>,
    ) : CopyPlan

    data class Blocked(
        val reason: String,
    ) : CopyPlan
}

/** The [CopyEnds] of a reconciliation's [shadowAccounts] and [links]. */
fun copyEnds(
    shadowAccounts: List<ShadowAccount>,
    links: List<ReconciliationLink>,
): CopyEnds =
    CopyEnds(
        realOf = links.groupBy({ it.shadowAccountId }, { it.realAccountId }),
        walletOfFeeAccount = shadowAccounts.mapNotNull { shadow -> shadow.feeAccountFor?.let { shadow.accountId to it } }.toMap(),
    )

/**
 * Plans copying a source [movement] (its trades, and its transfers such as a trade's fee) onto the real accounts
 * ([CopyEnds]): each wallet becomes the one real account linked to it, and a wallet's fee account becomes that real
 * account's fee account. A movement touching anything else (the source's "External" or income accounts) has no real
 * counterpart, so it is [CopyPlan.Blocked], as is one with only part of a trade unmatched or one the source excludes.
 */
@Suppress("ReturnCount")
fun planCopy(
    movement: List<ReconciliationLeg>,
    shadowAccounts: List<ShadowAccount>,
    links: List<ReconciliationLink>,
    accountNames: Map<AccountId, String>,
): CopyPlan {
    if (movement.isEmpty()) return CopyPlan.Blocked("Nothing to copy")
    if (movement.any { it.isExcluded }) return CopyPlan.Blocked("The source excludes this row")
    val ends = copyEnds(shadowAccounts, links)

    fun name(id: AccountId) = accountNames[id] ?: "account ${id.id}"

    fun problem(shadow: AccountId): String {
        val linked = ends.linkedAccountOf(shadow)
        return when (ends.linkCount(linked)) {
            0 -> "${name(linked)} is linked to no Money Manager account"
            1 -> "${name(shadow)} has no real account"
            else -> "${name(linked)} is linked to several Money Manager accounts"
        }
    }

    val trades = mutableListOf<CopiedTrade>()
    val transfers = mutableListOf<CopiedTransfer>()
    for ((_, legs) in movement.groupBy { it.kind to it.transactionId }) {
        val first = legs.first()
        val own = ends.end(first.accountId) as? CopyEnd.Real ?: return CopyPlan.Blocked(problem(first.accountId))
        when (first.kind) {
            LegTransactionKind.TRADE -> {
                val from = legs.singleOrNull { it.amount.isNegative() }
                val to = legs.singleOrNull { it.amount.isPositive() }
                if (from == null || to == null || legs.size != 2) return CopyPlan.Blocked("Only part of a trade is unmatched")
                trades += CopiedTrade(first.transactionRef, first.timestamp, first.description, own.account, -from.amount, to.amount)
            }

            LegTransactionKind.TRANSFER -> {
                val counterparty = first.counterpartyAccountId ?: return CopyPlan.Blocked("The transfer has no other side")
                val other = ends.end(counterparty) ?: return CopyPlan.Blocked(problem(counterparty))
                val outgoing = first.amount.isNegative()
                transfers +=
                    CopiedTransfer(
                        copies = first.transactionRef,
                        timestamp = first.timestamp,
                        description = first.description,
                        from = if (outgoing) own else other,
                        to = if (outgoing) other else own,
                        amount = if (outgoing) -first.amount else first.amount,
                    )
            }
        }
    }
    return CopyPlan.Ready(trades, transfers)
}

/**
 * Books [plan] on the real accounts and stores the copies as the match of [movement], so the source row they copy
 * counts as reconciled ([acceptMatches] pins the copies with the rest of the match). Each copy is
 * [ImportBatch.reconciliationCopies]' [Source.Reconciliation] transaction, observed by the source row's own record, so a
 * re-import of the source brings it up to date or removes it (#1475). Returns the copies.
 */
suspend fun ImportEngine.copyToMoneyManager(
    movement: List<ReconciliationLeg>,
    plan: CopyPlan.Ready,
    observations: ObservationReadRepository,
): List<TransactionId> {
    val feeOwners = plan.transfers.flatMap { listOf(it.from, it.to) }.filterIsInstance<CopyEnd.FeeAccountOf>()
    feeAccounts(feeOwners.mapTo(mutableSetOf()) { it.account }, Source.Reconciliation)
    val copies =
        import(ImportBatch(reconciliationCopies = plan.trades.map { it.copies } + plan.transfers.map { it.copies }))
            .copiedTransactionIds
    acceptMatches(listOf(movement.map { it.transactionRef }.distinct() + copies), observations)
    return copies
}
