package com.moneymanager.database.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.domain.model.CsvImportId
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
suspend fun MoneyManagerDatabaseWrapper.recordSource(
    deviceId: DeviceId,
    entityType: EntityType,
    entityId: Long,
    revisionId: Long,
    source: Source,
) {
    // INSERT OR IGNORE: a source for this (entity, revision) may already exist (e.g. re-runs); keep it.
    entitySourceWriteQueries.insertSource(
        entity_type_id = entityType.id,
        entity_id = entityId,
        revision_id = revisionId,
        source_type_id = source.toSourceType().id.toLong(),
        device_id = deviceId.id,
    )

    when (source) {
        // Record the import detail row so the import id (and thus the audit trail's link back to the
        // import) is preserved even when no single originating row/record is known — e.g. an account or
        // category derived from the import as a whole (the row/record index stays null). Guard on the
        // persisted source type (mirroring the API branch): INSERT OR IGNORE above keeps any pre-existing
        // row for this (entity, revision), so only attach CSV detail when that row is actually CSV.
        is Source.Csv -> {
            val entitySource = entitySourceSelectQueries.selectEntitySourceForRevision(entityType.id, entityId, revisionId).awaitAsOne()
            if (entitySource.source_type_id == SourceType.CSV_IMPORT.id.toLong()) {
                entitySourceWriteQueries.insertCsvSource(
                    id = entitySource.id,
                    csv_import_id = source.importId.id.toString(),
                    csv_row_index = source.rowIndex,
                )
            }
        }

        is Source.Api -> {
            val requestId = source.requestId
            val jsonPath = source.jsonPath
            // Attach the clickable request/JSON-path detail only when known, and preserve the existing
            // suppression: only when this revision's source is API and no API detail exists yet.
            if (requestId != null && jsonPath != null) {
                val entitySource =
                    entitySourceSelectQueries
                        .selectEntitySourceForRevision(
                            entityType.id,
                            entityId,
                            revisionId,
                        ).awaitAsOne()
                if (entitySource.source_type_id == SourceType.API.id.toLong() &&
                    entitySourceSelectQueries.selectApiEntitySourceId(entitySource.id).awaitAsOneOrNull() == null
                ) {
                    entitySourceWriteQueries.insertApiSource(
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
        -> {}
    }
}

/**
 * Records that a staged row ([source]) links to [transactionId], a transfer or
 * trade another record wrote (it matched it as a duplicate, or updated it), as a matched `entity_source` of
 * its current revision. Matched entries mirror the records' current links: a duplicate row changes nothing,
 * so without one its link would live only in its import's staging table, and re-importing the
 * transaction's own file would have to read every file to find it. Whoever replaces or clears a record's
 * link calls [forgetMatchedSources] first. Called inside the caller's transaction, like [recordSource].
 */
suspend fun MoneyManagerDatabaseWrapper.recordMatchedSource(
    deviceId: DeviceId,
    source: Source.Csv,
    transactionId: Long,
) {
    if (entitySourceWriteQueries.insertMatchedSource(source.toSourceType().id.toLong(), deviceId.id, transactionId) == 0L) {
        return
    }
    val id = entitySourceWriteQueries.lastInsertedSourceId().awaitAsOne()
    entitySourceWriteQueries.insertCsvSource(id, source.importId.id.toString(), source.rowIndex)
}

/**
 * Removes the matched entries of [rows] of [import], or of all its rows when null: their links are about to be
 * replaced or cleared. Called inside the caller's transaction.
 */
suspend fun MoneyManagerDatabaseWrapper.forgetMatchedSources(
    import: CsvImportId,
    rows: Collection<Long>?,
) {
    val selects = entitySourceSelectQueries
    val importId = import.id.toString()
    val ids =
        rows?.chunked(SOURCE_CHUNK)?.flatMap { selects.selectMatchedCsvSourceIds(importId, it).awaitAsList() }
            ?: selects.selectAllMatchedCsvSourceIds(importId).awaitAsList()
    ids.chunked(SOURCE_CHUNK).forEach { chunk ->
        entitySourceWriteQueries.deleteCsvEntitySources(chunk)
        entitySourceWriteQueries.deleteMatchedEntitySources(chunk)
    }
}

// Keeps each IN list well under SQLite's bound-parameter limit.
private const val SOURCE_CHUNK = 500
