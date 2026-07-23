package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForExchangeOrder
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.ExchangeOrderAuditEntry
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.SourceRecord
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
            source = from.toSourceRecord(),
        )
}

private fun SelectAuditHistoryForExchangeOrder.toSourceRecord(): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = source_id,
            sourceTypeName = source_type_name,
            deviceId = source_device_id,
            createdAt = source_created_at,
            entityType = EntityType.EXCHANGE_ORDER,
            entityId = exchange_order_id,
            revisionId = revision_id,
            detail =
                SourceDetailColumns(
                    platformName = source_platform_name,
                    osName = source_os_name,
                    machineName = source_machine_name,
                    deviceMake = source_device_make,
                    deviceModel = source_device_model,
                    csvImportId = source_csv_import_id,
                    csvRowIndex = source_csv_row_index,
                    csvFileName = source_csv_file_name,
                    qifImportId = source_qif_import_id,
                    qifRecordIndex = source_qif_record_index,
                    qifFileName = source_qif_file_name,
                    apiSessionId = source_api_session_id,
                    apiRequestId = source_api_request_id,
                    apiJsonPath = source_api_json_path,
                ),
        ),
    )
