package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForExchangeOrder
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.ExchangeOrderAuditEntry
import com.moneymanager.domain.model.ExchangeOrderId
import kotlin.time.Instant.Companion.fromEpochMilliseconds

object ExchangeOrderAuditEntryMapper {
    fun map(from: SelectAuditHistoryForExchangeOrder): ExchangeOrderAuditEntry =
        ExchangeOrderAuditEntry(
            id = from.id,
            auditTimestamp = fromEpochMilliseconds(from.audit_timestamp),
            auditType = AuditType.valueOf(from.audit_type.uppercase()),
            orderId = ExchangeOrderId(from.exchange_order_id),
            revisionId = from.revision_id,
            accountId = AccountId(from.account_id),
            orderRef = from.order_ref,
            clientOid = from.client_oid,
            side = from.side,
            orderType = from.order_type,
            timeInForce = from.time_in_force,
            status = from.status,
            limitPrice = from.limit_price,
            quantity = from.quantity,
            avgPrice = from.avg_price,
            createdAt = fromEpochMilliseconds(from.created_at),
            updatedAt = from.updated_at?.let(::fromEpochMilliseconds),
        )
}
