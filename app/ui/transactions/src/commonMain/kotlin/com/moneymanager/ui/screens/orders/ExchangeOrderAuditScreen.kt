package com.moneymanager.ui.screens.orders

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.ExchangeOrderAuditEntry
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.SourceInfoSection

/**
 * Revision history of one exchange order. Each entry shows the audited snapshot and its provenance;
 * an API-sourced revision links back to the exact request/JSON node of the session that imported it.
 */
@Composable
fun ExchangeOrderAuditScreen(
    orderId: ExchangeOrderId,
    auditRepository: AuditReadRepository,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "Order Audit: $orderId",
        entityTypeName = "order",
        loadKey = orderId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForExchangeOrder(orderId)
            AuditScreenData(
                title = "Order Audit: ${entries.firstOrNull()?.orderRef ?: orderId}",
                diffs = entries,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { entry -> ExchangeOrderAuditDiffCard(entry, onApiSourceClick) },
    )
}

@Composable
private fun ExchangeOrderAuditDiffCard(
    entry: ExchangeOrderAuditEntry,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit,
) {
    AuditDiffCard(
        auditType = entry.auditType,
        auditTimestamp = entry.auditTimestamp,
        revisionId = entry.revisionId,
    ) {
        // The audit triggers snapshot the row as it was (UPDATE records the pre-change values with
        // the new revision id), so each card is that revision's full field set.
        Text(
            text =
                when (entry.auditType) {
                    AuditType.INSERT -> "Created with:"
                    AuditType.UPDATE -> "Previous values:"
                    AuditType.DELETE -> "Deleted (final values):"
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FieldValueRow("Order id", entry.orderRef)
        FieldValueRow("Side", entry.side)
        entry.orderType?.let { FieldValueRow("Type", it) }
        entry.timeInForce?.let { FieldValueRow("Time in force", it) }
        entry.status?.let { FieldValueRow("Status", it) }
        entry.quantity?.let { FieldValueRow("Quantity", it) }
        entry.limitPrice?.let { FieldValueRow("Limit price", it) }
        entry.avgPrice?.let { FieldValueRow("Average price", it) }
        SourceInfoSection(entry.source, onApiSourceClick = onApiSourceClick)
    }
}
