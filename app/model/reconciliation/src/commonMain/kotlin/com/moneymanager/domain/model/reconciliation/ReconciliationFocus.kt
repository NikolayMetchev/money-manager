package com.moneymanager.domain.model.reconciliation

import com.moneymanager.domain.model.AccountId
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Where the Reconciliation tab should open: the [sourceName]'s [walletAccountId] wallet, at [month] (see
 * [reconciliationMonth]), with that wallet and month expanded so the movement a link was made for is in view.
 */
data class ReconciliationFocus(
    val sourceName: String,
    val walletAccountId: AccountId,
    val month: String,
)

/** The calendar month (UTC) the Reconciliation tab files [instant] under, e.g. `2021-05`. */
fun reconciliationMonth(instant: Instant): String {
    val date = instant.toLocalDateTime(TimeZone.UTC).date
    return "${date.year}-${date.month.ordinal.plus(1).toString().padStart(2, '0')}"
}
