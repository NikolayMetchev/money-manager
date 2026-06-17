@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.toSourceType

/**
 * Records an entity's `entity_source` (and any source-specific detail row) from a [Source]. Device
 * identity is injected ([deviceId]), not carried by the source.
 *
 * This is the single place all entity provenance is written. Repository create/update methods call it
 * INSIDE their own transaction (it does not open one), immediately after inserting/updating the
 * entity, so source recording is atomic with the change and can never be forgotten.
 */
internal fun EntitySourceQueries.recordSource(
    deviceId: DeviceId,
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
    source: Source,
) {
    // INSERT OR IGNORE: a source for this (entity, revision) may already exist (e.g. re-runs); keep it.
    insertSource(
        entity_type_id = entityType.id,
        entity_id = entityId,
        revision_id = revisionId,
        source_type_id = source.toSourceType().id.toLong(),
        device_id = deviceId.id,
    )

    when (source) {
        // Always record the import detail row so the import id (and thus the audit trail's link back
        // to the import) is preserved even when no single originating row/record is known — e.g. an
        // account or category derived from the import as a whole. The row/record index stays null.
        is Source.Csv ->
            insertCsvSource(
                id = entitySourceId(entityType, entityId, revisionId),
                csv_import_id = source.importId.id.toString(),
                csv_row_index = source.rowIndex,
            )
        is Source.Qif ->
            insertQifSource(
                id = entitySourceId(entityType, entityId, revisionId),
                qif_import_id = source.importId.id.toString(),
                qif_record_index = source.recordIndex,
            )
        is Source.Api -> {
            val requestId = source.requestId
            val jsonPath = source.jsonPath
            // Attach the clickable request/JSON-path detail only when known, and preserve the existing
            // suppression: only when this revision's source is API and no API detail exists yet.
            if (requestId != null && jsonPath != null) {
                val entitySource = selectEntitySourceForRevision(entityType.id, entityId, revisionId).executeAsOne()
                if (entitySource.source_type_id == SourceType.API.id.toLong() &&
                    selectApiEntitySourceId(entitySource.id).executeAsOneOrNull() == null
                ) {
                    insertApiSource(
                        id = entitySource.id,
                        api_session_id = source.sessionId.id,
                        api_request_id = requestId.id,
                        json_path = jsonPath.value,
                    )
                }
            }
        }
        Source.Manual,
        Source.SampleGenerator,
        Source.System,
        Source.Merge,
        Source.Unmerge,
        -> Unit
    }
}

private fun EntitySourceQueries.entitySourceId(
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
): Long = selectEntitySourceForRevision(entityType.id, entityId, revisionId).executeAsOne().id
