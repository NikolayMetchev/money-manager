package com.moneymanager.ui.screens.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.reconciliation.LegMatch
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.reconciliation.UnmatchedSourceLeg
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** One calendar month (UTC) of items within a wallet. */
data class MonthGroup<T>(
    /** `yyyy-MM`, which also sorts chronologically. */
    val month: String,
    val items: List<T>,
)

/** A wallet's items, split by month so a whole missing month (a missing export file) stands out. */
data class WalletGroup<T>(
    val walletAccountId: AccountId,
    val walletName: String,
    val months: List<MonthGroup<T>>,
) {
    val count: Int get() = months.sumOf { it.items.size }
}

/** Groups [items] by wallet (sorted by name) then month (chronological). */
fun <T> groupByWalletAndMonth(
    items: List<T>,
    walletOf: (T) -> AccountId,
    timeOf: (T) -> Instant,
    walletName: (AccountId) -> String,
): List<WalletGroup<T>> =
    items
        .groupBy(walletOf)
        .map { (wallet, walletItems) ->
            WalletGroup(
                walletAccountId = wallet,
                walletName = walletName(wallet),
                months =
                    walletItems
                        .sortedBy(timeOf)
                        .groupBy { monthKey(timeOf(it)) }
                        .map { (month, monthItems) -> MonthGroup(month, monthItems) },
            )
        }.sortedBy { it.walletName.lowercase() }

internal fun monthKey(instant: Instant): String {
    val date = instant.toLocalDateTime(TimeZone.UTC).date
    return "${date.year}-${date.month.ordinal.plus(1).toString().padStart(2, '0')}"
}

/** Per-wallet unmatched counts for the links section. */
data class WalletCounts(
    val missingInMm: Int,
    val missingInSource: Int,
)

fun ReconciliationResult.countsByWallet(hideUnknownAssets: Boolean): Map<AccountId, WalletCounts> {
    val mm = missingInMm.filter { !hideUnknownAssets || !it.assetUnknownToMm }.groupingBy { it.walletAccountId }.eachCount()
    val source = missingInSource.groupingBy { it.walletAccountId }.eachCount()
    return (mm.keys + source.keys).associateWith { WalletCounts(mm[it] ?: 0, source[it] ?: 0) }
}

/** The source legs of [leg]'s movement (a row and, say, its fee) still unmatched on its wallet: what one hand match covers. */
fun ReconciliationResult.movementOf(leg: UnmatchedSourceLeg): List<ReconciliationLeg> =
    missingInMm
        .filter { it.walletAccountId == leg.walletAccountId && it.leg.movementKey == leg.leg.movementKey }
        .map { it.leg }
        .ifEmpty { listOf(leg.leg) }

/** The source legs of every movement in [legs], each movement once: what a bulk ignore covers. */
fun ReconciliationResult.movementsOf(legs: List<UnmatchedSourceLeg>): List<ReconciliationLeg> = legs.flatMap { movementOf(it) }.distinct()

/**
 * The matches the matcher found that nobody has stored yet (exact and near alike), on [wallet] and in [month] when
 * given: what an "Accept all" stores, so a re-import can't change them.
 */
fun ReconciliationResult.unstoredMatches(
    wallet: AccountId? = null,
    month: String? = null,
): List<LegMatch> =
    matches.filter {
        it.matchId == null &&
            (wallet == null || it.sourceLeg.accountId == wallet) &&
            (month == null || monthKey(it.sourceLeg.timestamp) == month)
    }

/** Every transaction a near match spans, each once: the members of its `reconciliation-match` relationship. */
fun LegMatch.memberTransactions(): List<TransactionId> = (sourceLegs + realLegs).map { it.transactionRef }.distinct()

/** A real leg the match picker lists: selectable, or shown greyed because a near match already claims it. */
data class PickerCandidate(
    val leg: ReconciliationLeg,
    /** The leg's time minus the movement's. */
    val delta: Duration,
    /** The near match that claims this leg; accept or ignore that one first. Null when the leg is free. */
    val nearMatchFor: LegMatch? = null,
) {
    val selectable: Boolean get() = nearMatchFor == null
}

/**
 * The real legs a person may match [movement] (on [wallet]) to by hand: the linked accounts' legs no match claims,
 * within [window] of the movement, oldest first. Legs a near match claims are listed too, unselectable, so a missing
 * likely candidate is explained; legs in a stored or exact match never are.
 */
fun ReconciliationResult.pickerCandidates(
    movement: List<ReconciliationLeg>,
    wallet: AccountId,
    window: Duration,
    sameAssetOnly: Boolean,
): List<PickerCandidate> {
    val at = movement.minOf { it.timestamp }
    val assets = movement.mapTo(mutableSetOf()) { it.amount.asset.id }

    fun eligible(leg: ReconciliationLeg) = (!sameAssetOnly || leg.amount.asset.id in assets) && (leg.timestamp - at).absoluteValue <= window

    val free =
        unclaimedReal
            .filter { it.walletAccountId == wallet && eligible(it.leg) }
            .map { PickerCandidate(it.leg, it.leg.timestamp - at) }
    val claimed =
        nearMatches
            .filter { match -> match.sourceLeg.accountId == wallet }
            .flatMap { match -> match.realLegs.filter(::eligible).map { PickerCandidate(it, it.timestamp - at, match) } }
    return (free + claimed).sortedWith(compareBy({ it.leg.timestamp }, { it.leg.transactionId }))
}

/** One asset's totals in a hand match: the source movement's, the selected real legs', and how far apart they are. */
data class SelectionTotal(
    val asset: Asset,
    val source: Money,
    val selected: Money,
) {
    val difference: Money get() = selected - source

    val balanced: Boolean get() = selected.amount == source.amount
}

/** Per-asset totals of [movement] against [selected], in the order the assets first appear. */
fun selectionTotals(
    movement: List<ReconciliationLeg>,
    selected: List<ReconciliationLeg>,
): List<SelectionTotal> =
    (movement + selected)
        .map { it.amount.asset }
        .distinctBy { it.id }
        .map { asset ->
            fun sum(legs: List<ReconciliationLeg>) =
                legs.filter { it.amount.asset.id == asset.id }.fold(Money.zero(asset)) { total, leg -> total + leg.amount }
            SelectionTotal(asset, sum(movement), sum(selected))
        }
