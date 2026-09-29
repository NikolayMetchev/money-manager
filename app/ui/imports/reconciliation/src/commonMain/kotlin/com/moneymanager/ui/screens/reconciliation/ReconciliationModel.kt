package com.moneymanager.ui.screens.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.reconciliation.ReconciliationResult
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
