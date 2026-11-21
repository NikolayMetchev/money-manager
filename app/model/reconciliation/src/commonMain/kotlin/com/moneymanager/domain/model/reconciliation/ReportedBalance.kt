package com.moneymanager.domain.model.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecord
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@JvmInline
value class ReportedBalanceId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

/**
 * A balance a statement reports for an account once one of its records had happened (a `Balance` rule action, #1531).
 */
data class ReportedBalance(
    val id: ReportedBalanceId,
    val timestamp: Instant,
    val amount: Money,
    val revisionId: Long,
)

/** An account and asset some statement reports balances for. */
data class ReportedBalanceSeries(
    val accountId: AccountId,
    val accountName: String,
    val asset: Asset,
    val count: Long,
    val latest: Instant,
)

/**
 * One revision of a reported balance, with its provenance. [amount] is the stored integer at the scale of [assetId], as
 * the audit row keeps it.
 */
data class ReportedBalanceAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val balanceId: ReportedBalanceId,
    val revisionId: Long,
    val recordId: Long,
    val accountId: AccountId,
    val assetId: Long,
    val timestamp: Instant,
    val amount: String,
    val source: SourceRecord? = null,
)
