@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

/** Manual entry from UI. */
class ManualSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertManual(
            transfer.id.id,
            transfer.revisionId,
            deviceId.id,
        )
    }
}

/** Generated sample data. */
class SampleGeneratorSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertSampleGenerator(
            transfer.id.id,
            transfer.revisionId,
            deviceId.id,
        )
    }
}

/** CSV import with row tracking. */
class CsvImportSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
    private val csvImportId: CsvImportId,
    private val rowIndexForTransfer: (TransferId) -> Long,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.transaction {
            queries.insertCsvImportBase(
                transfer.id.id,
                transfer.revisionId,
                deviceId.id,
            )
            val transferSourceId = queries.lastInsertedId().executeAsOne()
            queries.insertCsvImportDetails(
                transferSourceId,
                csvImportId.id.toString(),
                rowIndexForTransfer(transfer.id),
            )
        }
    }
}

/** API import with session and request tracking. */
class ApiImportSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
    private val sessionId: ApiSessionId,
    private val requestId: ApiRequestId,
    private val jsonPath: JsonPath,
) : SourceRecorder {
    /** The ID of the transfer inserted by the most recent [insert] call. */
    var insertedTransferId: TransferId? = null
        private set

    override fun insert(transfer: Transfer) {
        insertedTransferId = transfer.id
        queries.transaction {
            queries.insertApiBase(
                transfer.id.id,
                transfer.revisionId,
                deviceId.id,
            )
            val transferSourceId = queries.lastInsertedId().executeAsOne()
            queries.insertApiDetails(
                transferSourceId,
                sessionId.id,
                requestId.id,
                jsonPath.value,
            )
        }
    }
}

// ============================================================================
// Entity Source Recorders (for Account, Person, Currency, PersonAccountOwnership)
// ============================================================================

/** Records source for entity operations (manual entry). */
class ManualEntitySourceRecorder(
    private val queries: EntitySourceQueries,
    private val deviceId: DeviceId,
) {
    fun insert(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) {
        queries.insertSource(
            entity_type_id = entityType.id,
            entity_id = entityId,
            revision_id = revisionId,
            source_type_id = SourceType.MANUAL.id.toLong(),
            device_id = deviceId.id,
        )
    }
}

/** Records source for entity operations (sample data generator). */
class SampleGeneratorEntitySourceRecorder(
    private val queries: EntitySourceQueries,
    private val deviceId: DeviceId,
) {
    fun insert(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) {
        queries.insertSource(
            entity_type_id = entityType.id,
            entity_id = entityId,
            revision_id = revisionId,
            source_type_id = SourceType.SAMPLE_GENERATOR.id.toLong(),
            device_id = deviceId.id,
        )
    }
}

/** Records source for entity operations (API import). */
class ApiEntitySourceRecorder(
    private val queries: EntitySourceQueries,
    private val deviceId: DeviceId,
    private val sessionId: ApiSessionId,
    private val requestId: ApiRequestId,
    private val jsonPath: JsonPath,
) {
    fun insert(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) {
        runCatching {
            queries.transaction {
                queries.insertSource(
                    entity_type_id = entityType.id,
                    entity_id = entityId,
                    revision_id = revisionId,
                    source_type_id = SourceType.API.id.toLong(),
                    device_id = deviceId.id,
                )
                val entitySourceId = queries.lastInsertedId().executeAsOne()
                queries.insertApiSource(
                    id = entitySourceId,
                    api_session_id = sessionId.id,
                    api_request_id = requestId.id,
                    json_path = jsonPath.value,
                )
            }
        }.onFailure { error ->
            val isDuplicateRevisionSource =
                error.message?.contains("UNIQUE constraint failed: entity_source.entity_type_id, entity_source.entity_id, entity_source.revision_id") ==
                    true
            if (!isDuplicateRevisionSource) {
                throw error
            }
        }
    }
}
