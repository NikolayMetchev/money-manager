@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForCurrency
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecord
import tech.mappie.api.ObjectMappie

object CurrencyAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCurrency, CurrencyAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCurrency): CurrencyAuditEntry =
        mapping {
            CurrencyAuditEntry::source fromValue from.toSourceRecord()
        }
}

private fun SelectAuditHistoryForCurrency.toSourceRecord(): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = source_id,
            sourceTypeName = source_type_name,
            deviceId = source_device_id,
            createdAt = source_created_at,
            entityType = EntityType.CURRENCY,
            entityId = currency_id,
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
