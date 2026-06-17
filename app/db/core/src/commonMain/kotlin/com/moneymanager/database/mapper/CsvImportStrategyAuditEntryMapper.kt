@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.database.sql.SelectAuditHistoryForCsvImportStrategy
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CsvImportStrategyAuditEntry
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlin.time.Instant
import kotlin.uuid.Uuid

object CsvImportStrategyAuditEntryMapper {
    fun map(from: SelectAuditHistoryForCsvImportStrategy): CsvImportStrategyAuditEntry {
        // The csv_import_strategy_source table has no per-type detail rows (strategies are only
        // manually/system created), so the import-detail columns are absent here.
        val source =
            buildSourceRecord(
                SourceColumns(
                    sourceId = from.source_id,
                    sourceTypeName = from.source_type_name,
                    deviceId = from.source_device_id,
                    createdAt = from.source_created_at,
                    entityType = EntityType.CSV_IMPORT_STRATEGY,
                    entityId = 0,
                    revisionId = from.revision_id,
                    detail =
                        SourceDetailColumns(
                            platformName = from.source_platform_name,
                            osName = from.source_os_name,
                            machineName = from.source_machine_name,
                            deviceMake = from.source_device_make,
                            deviceModel = from.source_device_model,
                            csvImportId = null,
                            csvRowIndex = null,
                            csvFileName = null,
                            qifImportId = null,
                            qifRecordIndex = null,
                            qifFileName = null,
                            apiSessionId = null,
                            apiRequestId = null,
                            apiJsonPath = null,
                        ),
                ),
            )
        return CsvImportStrategyAuditEntry(
            id = from.id,
            auditTimestamp = Instant.fromEpochMilliseconds(from.audit_timestamp),
            auditType = AuditType.valueOf(from.audit_type.uppercase()),
            strategyId = CsvImportStrategyId(Uuid.parse(from.csv_import_strategy_id)),
            revisionId = from.revision_id,
            name = from.name,
            identificationColumns = FieldMappingJsonCodec.decodeColumns(from.identification_columns_json),
            fieldMappings = FieldMappingJsonCodec.decode(from.field_mappings_json),
            attributeMappings = FieldMappingJsonCodec.decodeAttributeMappings(from.attribute_mappings_json),
            rowPreprocessingRules = FieldMappingJsonCodec.decodeRowRules(from.row_rules_json),
            companionTransactionRules = FieldMappingJsonCodec.decodeCompanionRules(from.companion_rules_json),
            createdAt = Instant.fromEpochMilliseconds(from.created_at),
            updatedAt = Instant.fromEpochMilliseconds(from.updated_at),
            source = source,
        )
    }
}
