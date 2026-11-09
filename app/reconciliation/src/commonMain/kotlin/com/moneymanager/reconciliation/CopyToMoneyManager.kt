package com.moneymanager.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.createReconciliationMatch
import com.moneymanager.importengineapi.feeAccounts
import kotlin.time.Instant

/** Where a copied transfer's end lands among the real accounts. */
sealed interface CopyEnd {
    /** The real account a linked wallet stands for. */
    data class Real(
        val account: AccountId,
    ) : CopyEnd

    /** The fee account of [account], the real account whose wallet's fee account the source paid into. */
    data class FeeAccountOf(
        val account: AccountId,
    ) : CopyEnd
}

data class CopiedTrade(
    val timestamp: Instant,
    val description: String,
    val account: AccountId,
    val from: Money,
    val to: Money,
)

data class CopiedTransfer(
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

/**
 * Plans copying a source [movement] (its trades, and its transfers such as a trade's fee) onto the real accounts:
 * each wallet becomes the one real account linked to it, and a wallet's fee account becomes that real account's fee
 * account. A movement touching anything else (the source's "External" or income accounts) has no real counterpart, so
 * it is [CopyPlan.Blocked], as is one with only part of a trade unmatched or one the source excludes.
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
    val realOf = links.groupBy({ it.shadowAccountId }, { it.realAccountId })
    val shadowById = shadowAccounts.associateBy { it.accountId }

    fun name(id: AccountId) = accountNames[id] ?: "account ${id.id}"

    fun end(shadow: AccountId): Pair<CopyEnd?, String?> {
        shadowById[shadow]?.feeAccountFor?.let { wallet ->
            val (owner, problem) = end(wallet)
            return (owner as? CopyEnd.Real)?.let { CopyEnd.FeeAccountOf(it.account) } to problem
        }
        val real = realOf[shadow].orEmpty()
        return when (real.size) {
            1 -> CopyEnd.Real(real.single()) to null
            0 -> null to "${name(shadow)} is linked to no Money Manager account"
            else -> null to "${name(shadow)} is linked to several Money Manager accounts"
        }
    }

    val trades = mutableListOf<CopiedTrade>()
    val transfers = mutableListOf<CopiedTransfer>()
    for ((_, legs) in movement.groupBy { it.kind to it.transactionId }) {
        val first = legs.first()
        val (own, ownProblem) = end(first.accountId)
        if (own !is CopyEnd.Real) return CopyPlan.Blocked(ownProblem ?: "${name(first.accountId)} has no real account")
        when (first.kind) {
            LegTransactionKind.TRADE -> {
                val from = legs.singleOrNull { it.amount.isNegative() }
                val to = legs.singleOrNull { it.amount.isPositive() }
                if (from == null || to == null || legs.size != 2) return CopyPlan.Blocked("Only part of a trade is unmatched")
                trades += CopiedTrade(first.timestamp, first.description, own.account, -from.amount, to.amount)
            }

            LegTransactionKind.TRANSFER -> {
                val counterparty = first.counterpartyAccountId ?: return CopyPlan.Blocked("The transfer has no other side")
                val (other, problem) = end(counterparty)
                if (other == null) return CopyPlan.Blocked(problem ?: "${name(counterparty)} has no real account")
                val outgoing = first.amount.isNegative()
                transfers +=
                    CopiedTransfer(
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
 * Books [plan] on the real accounts as [Source.Reconciliation] records, and stores them as the match of [movement],
 * so the source row they copy counts as reconciled. Returns the copies.
 */
suspend fun ImportEngine.copyToMoneyManager(
    movement: List<ReconciliationLeg>,
    plan: CopyPlan.Ready,
): List<TransactionId> {
    val source = Source.Reconciliation
    val feeOwners = plan.transfers.flatMap { listOf(it.from, it.to) }.filterIsInstance<CopyEnd.FeeAccountOf>()
    val feeAccounts = feeAccounts(feeOwners.mapTo(mutableSetOf()) { it.account }, source)

    fun ref(end: CopyEnd) =
        AccountRef.Existing(
            when (end) {
                is CopyEnd.Real -> end.account
                is CopyEnd.FeeAccountOf -> feeAccounts.getValue(end.account)
            },
        )

    val tradeKeys = plan.trades.indices.map { LocalTradeKey("copy-$it") }
    val rowKeys = plan.transfers.indices.map { ImportRowKey.Manual(it.toLong()) }
    val result =
        import(
            ImportBatch(
                trades =
                    plan.trades.mapIndexed { index, trade ->
                        ImportTradeIntent(
                            key = tradeKeys[index],
                            source = source,
                            timestamp = trade.timestamp,
                            description = trade.description,
                            fromAccountId = trade.account,
                            fromAmount = trade.from,
                            toAccountId = trade.account,
                            toAmount = trade.to,
                        )
                    },
                transfers =
                    plan.transfers.mapIndexed { index, transfer ->
                        ImportTransfer(
                            source = source,
                            rowKey = rowKeys[index],
                            fromAccount = ref(transfer.from),
                            toAccount = ref(transfer.to),
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            amount = transfer.amount,
                        )
                    },
                dedupePolicy = DedupePolicy.None,
            ),
        )
    val copies =
        tradeKeys.map { requireNotNull(result.createdTradeIds[it]) { "A trade was not copied" } } +
            rowKeys.map { requireNotNull(result.createdTransferIds[it]) { "A transfer was not copied" } }
    createReconciliationMatch(movement.map { it.transactionRef }.distinct() + copies)
    return copies
}
