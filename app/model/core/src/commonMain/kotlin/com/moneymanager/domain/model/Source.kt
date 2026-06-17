package com.moneymanager.domain.model

import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId

/**
 * Describes where an entity (account/person/ownership/category/currency/transfer) came from, so its
 * source row can be recorded.
 *
 * Device identity is deliberately **not** part of a [Source]: the current device is resolved once on
 * startup and injected into whatever writes audit rows (the repositories and the import engine), so a
 * [Source] value carries only origin-specific detail. Every entity built in an import batch carries a
 * [Source] (see [Auditable]); the import engine knows how to persist each variant. Each variant maps
 * to a [SourceType].
 */
sealed interface Source {
    /** Manual entry from the UI. */
    data object Manual : Source

    /** Generated sample data. */
    data object SampleGenerator : Source

    /** System-generated (e.g. seeded currencies/categories). */
    data object System : Source

    /** Deleted/reassigned by merging an account into another (see AccountRepository.mergeAccounts). */
    data object Merge : Source

    /** Recreated/reassigned by undoing an account merge (see AccountRepository.unmergeAccount). */
    data object Unmerge : Source

    /** Created during a CSV import; [rowIndex] is the originating row when known. */
    data class Csv(
        val importId: CsvImportId,
        val rowIndex: Long? = null,
    ) : Source

    /** Created during a QIF import; [recordIndex] is the originating record when known. */
    data class Qif(
        val importId: QifImportId,
        val recordIndex: Long? = null,
    ) : Source

    /**
     * Created during an API import. [requestId]/[jsonPath] pinpoint the originating response node when
     * known; when absent only the API source type is recorded (no clickable request/JSON-path detail).
     */
    data class Api(
        val sessionId: ApiSessionId,
        val requestId: ApiRequestId? = null,
        val jsonPath: JsonPath? = null,
    ) : Source
}

/**
 * Maps a [Source] to its persisted [SourceType] discriminator. [Source.Unmerge] keeps the historical
 * `MERGE_UNDO` source type so existing audit-trail labels remain stable.
 */
fun Source.toSourceType(): SourceType =
    when (this) {
        Source.Manual -> SourceType.MANUAL
        Source.SampleGenerator -> SourceType.SAMPLE_GENERATOR
        Source.System -> SourceType.SYSTEM
        Source.Merge -> SourceType.MERGE
        Source.Unmerge -> SourceType.MERGE_UNDO
        is Source.Csv -> SourceType.CSV_IMPORT
        is Source.Qif -> SourceType.QIF_IMPORT
        is Source.Api -> SourceType.API
    }
