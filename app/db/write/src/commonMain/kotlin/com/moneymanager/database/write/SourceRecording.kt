package com.moneymanager.database.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
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
        // row for this (entity, revision), so only attach CSV/QIF detail when that row is actually CSV/QIF.
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

        is Source.Qif -> {
            val entitySource = entitySourceSelectQueries.selectEntitySourceForRevision(entityType.id, entityId, revisionId).awaitAsOne()
            if (entitySource.source_type_id == SourceType.QIF_IMPORT.id.toLong()) {
                entitySourceWriteQueries.insertQifSource(
                    id = entitySource.id,
                    qif_import_id = source.importId.id.toString(),
                    qif_record_index = source.recordIndex,
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
 * Records that an import record ([source]: a CSV row or QIF record) links to [transactionId], a transfer or
 * trade another record wrote (it matched it as a duplicate, or updated it), as a matched `entity_source` of
 * its current revision. Matched entries mirror the records' current links: a duplicate row changes nothing,
 * so without one its link would live only in its import's staging table, and re-importing the
 * transaction's own file would have to read every file to find it. Whoever replaces or clears a record's
 * link calls [forgetMatchedSources] first. Called inside the caller's transaction, like [recordSource].
 */
suspend fun MoneyManagerDatabaseWrapper.recordMatchedSource(
    deviceId: DeviceId,
    source: Source,
    transactionId: Long,
) {
    if (entitySourceWriteQueries.insertMatchedSource(source.toSourceType().id.toLong(), deviceId.id, transactionId) == 0L) {
        return
    }
    val id = entitySourceWriteQueries.lastInsertedSourceId().awaitAsOne()
    when (source) {
        is Source.Csv -> entitySourceWriteQueries.insertCsvSource(id, source.importId.id.toString(), source.rowIndex)
        is Source.Qif -> entitySourceWriteQueries.insertQifSource(id, source.importId.id.toString(), source.recordIndex)
        else -> error("Only an import record can match a transaction: $source")
    }
}

/**
 * Removes the matched entries of [records] of [import] (a whole CSV or QIF import), or of all its records
 * when null: their links are about to be replaced or cleared. Called inside the caller's transaction.
 */
suspend fun MoneyManagerDatabaseWrapper.forgetMatchedSources(
    import: Source,
    records: Collection<Long>?,
) {
    val selects = entitySourceSelectQueries
    val ids =
        when (import) {
            is Source.Csv -> {
                val importId = import.importId.id.toString()
                records?.chunked(SOURCE_CHUNK)?.flatMap { selects.selectMatchedCsvSourceIds(importId, it).awaitAsList() }
                    ?: selects.selectAllMatchedCsvSourceIds(importId).awaitAsList()
            }

            is Source.Qif -> {
                val importId = import.importId.id.toString()
                records?.chunked(SOURCE_CHUNK)?.flatMap { selects.selectMatchedQifSourceIds(importId, it).awaitAsList() }
                    ?: selects.selectAllMatchedQifSourceIds(importId).awaitAsList()
            }

            else -> {
                error("Only an import's records have matched sources: $import")
            }
        }
    ids.chunked(SOURCE_CHUNK).forEach { chunk ->
        when (import) {
            is Source.Csv -> entitySourceWriteQueries.deleteCsvEntitySources(chunk)
            else -> entitySourceWriteQueries.deleteQifEntitySources(chunk)
        }
        entitySourceWriteQueries.deleteMatchedEntitySources(chunk)
    }
}

// Keeps each IN list well under SQLite's bound-parameter limit.
private const val SOURCE_CHUNK = 500
