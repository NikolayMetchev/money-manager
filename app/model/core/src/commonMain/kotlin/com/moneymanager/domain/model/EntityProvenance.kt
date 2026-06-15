package com.moneymanager.domain.model

import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId

/**
 * Describes where an auditable entity (account/person/ownership/category/currency) came from, so its
 * `entity_source` row can be recorded atomically with creation/update.
 *
 * Repository create/update methods require this as a non-defaulted parameter: provenance can no
 * longer be forgotten, because you cannot create an entity without supplying it. Each variant maps to
 * a [SourceType]; the row/record indices are optional so callers that don't know the originating row
 * still record the source type.
 */
sealed interface EntityProvenance {
    val deviceId: DeviceId

    /** Manual entry from the UI. */
    data class Manual(
        override val deviceId: DeviceId,
    ) : EntityProvenance

    /** Generated sample data. */
    data class SampleGenerator(
        override val deviceId: DeviceId,
    ) : EntityProvenance

    /** System-generated (e.g. seeded currencies/categories). */
    data class System(
        override val deviceId: DeviceId,
    ) : EntityProvenance

    /** Deleted by merging the account into another (see AccountRepository.mergeAccounts). */
    data class Merge(
        override val deviceId: DeviceId,
    ) : EntityProvenance

    /** Recreated by undoing an account merge (see AccountRepository.unmergeAccount). */
    data class MergeUndo(
        override val deviceId: DeviceId,
    ) : EntityProvenance

    /** Created during a CSV import; [rowIndex] is the originating row when known. */
    data class CsvImport(
        override val deviceId: DeviceId,
        val importId: CsvImportId,
        val rowIndex: Long? = null,
    ) : EntityProvenance

    /** Created during a QIF import; [recordIndex] is the originating record when known. */
    data class QifImport(
        override val deviceId: DeviceId,
        val importId: QifImportId,
        val recordIndex: Long? = null,
    ) : EntityProvenance

    /**
     * Created during an API import. [requestId]/[jsonPath] pinpoint the originating response node when
     * known; when absent only the API source type is recorded (no clickable request/JSON-path detail).
     */
    data class ApiImport(
        override val deviceId: DeviceId,
        val sessionId: ApiSessionId,
        val requestId: ApiRequestId? = null,
        val jsonPath: JsonPath? = null,
    ) : EntityProvenance
}
