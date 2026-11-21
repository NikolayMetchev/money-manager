package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForReportedBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.reconciliation.ReportedBalanceAuditEntry
import com.moneymanager.domain.model.reconciliation.ReportedBalanceId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object ReportedBalanceAuditEntryMapper {
    fun map(from: SelectAuditHistoryForReportedBalance): ReportedBalanceAuditEntry =
        ReportedBalanceAuditEntry(
            id = from.id,
            auditTimestamp = fromEpochMilliseconds(from.audit_timestamp),
            auditType = AuditType.valueOf(from.audit_type.uppercase()),
            balanceId = ReportedBalanceId(from.reported_balance_id),
            revisionId = from.revision_id,
            recordId = from.record_id,
            accountId = AccountId(from.account_id),
            assetId = from.asset_id,
            timestamp = fromEpochMilliseconds(from.timestamp),
            amount = from.amount,
        )
}
