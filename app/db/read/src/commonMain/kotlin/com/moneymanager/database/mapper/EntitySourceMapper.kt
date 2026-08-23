package com.moneymanager.database.mapper

import com.moneymanager.database.sql.entitySource.SelectEntitySources
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecord

/**
 * Turns one `selectEntitySources` row into the read-side [SourceRecord]. The single place a row of
 * the unified `entity_source` store is mapped: every entity type shares this query, so — unlike the
 * per-entity audit queries it replaced — the flat source columns are spelled out once.
 *
 * [entityType] is the type the query was run for; it is the query's own filter, so it is passed in
 * rather than re-read from the row.
 */
fun SelectEntitySources.toSourceRecord(entityType: EntityType): SourceRecord? =
    buildSourceRecord(
        SourceColumns(
            sourceId = id,
            sourceTypeName = source_type,
            deviceId = device_id,
            createdAt = created_at,
            entityType = entityType,
            entityId = entity_id,
            revisionId = revision_id,
            platformName = platform_name,
            osName = os_name,
            machineName = machine_name,
            deviceMake = device_make,
            deviceModel = device_model,
            csvImportId = csv_import_id,
            csvRowIndex = csv_row_index,
            csvFileName = csv_file_name,
            qifImportId = qif_import_id,
            qifRecordIndex = qif_record_index,
            qifFileName = qif_file_name,
            apiSessionId = api_session_id,
            apiRequestId = api_request_id,
            apiJsonPath = api_json_path,
        ),
    )
