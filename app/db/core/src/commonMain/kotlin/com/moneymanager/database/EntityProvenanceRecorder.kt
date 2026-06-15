@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceType

/**
 * Records an entity's `entity_source` (and any source-specific detail row) from an [EntityProvenance].
 *
 * This is the single place all entity provenance is written. Repository create/update methods call it
 * INSIDE their own transaction (it does not open one), immediately after inserting/updating the entity,
 * so source recording is atomic with the change and can never be forgotten.
 */
internal fun EntitySourceQueries.recordEntityProvenance(
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
    provenance: EntityProvenance,
) {
    val sourceType =
        when (provenance) {
            is EntityProvenance.Manual -> SourceType.MANUAL
            is EntityProvenance.SampleGenerator -> SourceType.SAMPLE_GENERATOR
            is EntityProvenance.System -> SourceType.SYSTEM
            is EntityProvenance.MergeUndo -> SourceType.MERGE_UNDO
            is EntityProvenance.CsvImport -> SourceType.CSV_IMPORT
            is EntityProvenance.QifImport -> SourceType.QIF_IMPORT
            is EntityProvenance.ApiImport -> SourceType.API
        }
    // INSERT OR IGNORE: a source for this (entity, revision) may already exist (e.g. re-runs); keep it.
    insertSource(
        entity_type_id = entityType.id,
        entity_id = entityId,
        revision_id = revisionId,
        source_type_id = sourceType.id.toLong(),
        device_id = provenance.deviceId.id,
    )

    when (provenance) {
        is EntityProvenance.CsvImport ->
            provenance.rowIndex?.let { row ->
                insertCsvSource(
                    id = entitySourceId(entityType, entityId, revisionId),
                    csv_import_id = provenance.importId.id.toString(),
                    csv_row_index = row,
                )
            }
        is EntityProvenance.QifImport ->
            provenance.recordIndex?.let { record ->
                insertQifSource(
                    id = entitySourceId(entityType, entityId, revisionId),
                    qif_import_id = provenance.importId.id.toString(),
                    qif_record_index = record,
                )
            }
        is EntityProvenance.ApiImport -> {
            val requestId = provenance.requestId
            val jsonPath = provenance.jsonPath
            // Attach the clickable request/JSON-path detail only when known, and preserve the existing
            // suppression: only when this revision's source is API and no API detail exists yet.
            if (requestId != null && jsonPath != null) {
                val source = selectEntitySourceForRevision(entityType.id, entityId, revisionId).executeAsOne()
                if (source.source_type_id == SourceType.API.id.toLong() &&
                    selectApiEntitySourceId(source.id).executeAsOneOrNull() == null
                ) {
                    insertApiSource(
                        id = source.id,
                        api_session_id = provenance.sessionId.id,
                        api_request_id = requestId.id,
                        json_path = jsonPath.value,
                    )
                }
            }
        }
        is EntityProvenance.Manual,
        is EntityProvenance.SampleGenerator,
        is EntityProvenance.System,
        is EntityProvenance.MergeUndo,
        -> Unit
    }
}

private fun EntitySourceQueries.entitySourceId(
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
): Long = selectEntitySourceForRevision(entityType.id, entityId, revisionId).executeAsOne().id
