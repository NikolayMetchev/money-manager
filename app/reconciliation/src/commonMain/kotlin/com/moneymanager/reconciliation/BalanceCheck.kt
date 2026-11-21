package com.moneymanager.reconciliation

import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import kotlin.time.Instant

/**
 * One instant a statement reports the account's balance at, against what Money Manager's own legs add up to then.
 *
 * [reported] are every balance reported at [timestamp], in statement order; [reportedBalance] is the one compared.
 * [difference] is `reportedBalance − computed`.
 */
data class BalanceCheckpoint(
    val timestamp: Instant,
    val reported: List<ReportedBalance>,
    val reportedBalance: Money,
    val computed: Money,
) {
    val difference: Money get() = reportedBalance - computed
}

/**
 * A checkpoint whose [BalanceCheckpoint.difference] isn't the one before it: something between [since] (exclusive)
 * and the checkpoint (inclusive) moved the balance by [change] more than the statement says, or less. [suspects] are
 * Money Manager's counted legs in that window, the transactions that can explain it.
 */
data class BalanceBreak(
    val checkpoint: BalanceCheckpoint,
    val since: Instant?,
    val change: Money,
    val suspects: List<ReconciliationLeg>,
)

/**
 * The comparison of one account's reported balances of one asset with its legs. [openingOffset] is the first
 * checkpoint's difference: the balance the account held before the first leg Money Manager has, which is not a break.
 */
data class BalanceCheckResult(
    val asset: Asset,
    val checkpoints: List<BalanceCheckpoint>,
    val openingOffset: Money?,
    val breaks: List<BalanceBreak>,
) {
    val latest: BalanceCheckpoint? get() = checkpoints.lastOrNull()
}

/**
 * Compares the balances a statement reports for one account in [asset] with the running balance of its [legs]
 * (any assets: only [asset]'s are read, and excluded legs aren't counted, as balances don't count them).
 *
 * Balances are compared per instant, never per transaction, so the order of records within a second, or a fee booked
 * as a transfer of its own, never shows as a break. At an instant reported several times (two records, or two
 * sources of one account) the balance compared is the one that keeps the previous difference, if any does, else the
 * last in statement order: the balance after every record of that instant.
 *
 * A leg counts from its transaction's [balanceTimes] entry when it has one, else from its own time: a statement whose
 * running balance counts a record when it completed (Revolut) counts a card payment started two days before then, so
 * comparing at its start would break twice, once each way.
 */
fun checkBalances(
    asset: Asset,
    reported: List<ReportedBalance>,
    legs: List<ReconciliationLeg>,
    balanceTimes: Map<Long, Instant> = emptyMap(),
): BalanceCheckResult {
    val countsAt: (ReconciliationLeg) -> Instant = { balanceTimes[it.transactionId] ?: it.timestamp }
    val counted = legs.filter { it.amount.asset.id == asset.id && !it.isExcluded }.sortedBy(countsAt)
    val byInstant =
        reported
            .filter { it.amount.asset.id == asset.id }
            .groupBy { it.timestamp }
            .entries
            .sortedBy { it.key }
    val checkpoints = mutableListOf<BalanceCheckpoint>()
    val breaks = mutableListOf<BalanceBreak>()
    var running = Money.zero(asset)
    var legIndex = 0
    var previous: BalanceCheckpoint? = null
    for ((timestamp, balances) in byInstant) {
        val windowStart = legIndex
        while (legIndex < counted.size && countsAt(counted[legIndex]) <= timestamp) running += counted[legIndex++].amount
        val keepsDifference = previous?.difference?.let { offset -> balances.lastOrNull { it.amount - running == offset } }
        val checkpoint =
            BalanceCheckpoint(
                timestamp = timestamp,
                reported = balances,
                reportedBalance = (keepsDifference ?: balances.last()).amount,
                computed = running,
            )
        previous?.let { before ->
            val change = checkpoint.difference - before.difference
            if (!change.isZero()) {
                breaks += BalanceBreak(checkpoint, before.timestamp, change, counted.subList(windowStart, legIndex).toList())
            }
        }
        checkpoints += checkpoint
        previous = checkpoint
    }
    return BalanceCheckResult(asset, checkpoints, checkpoints.firstOrNull()?.difference, breaks)
}
