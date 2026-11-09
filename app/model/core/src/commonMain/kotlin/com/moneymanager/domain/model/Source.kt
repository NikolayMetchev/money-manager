package com.moneymanager.domain.model

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

    /** Deleted/reassigned by merging an account into another (see AccountWriteRepository.mergeAccounts). */
    data object Merge : Source

    /** Recreated/reassigned by undoing an account merge (see AccountWriteRepository.unmergeAccount). */
    data object Unmerge : Source

    /**
     * Copied from a reconciliation source (Koinly, Accointing) onto real accounts, for a record the real accounts' own
     * sources lack. The copy is matched to the source's record by a `reconciliation-match` relationship.
     */
    data object Reconciliation : Source

    /**
     * Created during a CSV import (a QIF, Excel or PDF file is staged as one too); [rowIndex] is the originating row when
     * known. [statementAccountId] is the account whose statement the row is (see [Source.statementAccountId]), which the
     * engine sets from the transfer's `statementAccount`.
     */
    data class Csv(
        val importId: CsvImportId,
        val rowIndex: Long? = null,
        val statementAccountId: AccountId? = null,
    ) : Source

    /**
     * Created during an API import. [requestId]/[jsonPath] pinpoint the originating response node when
     * known; when absent only the API source type is recorded (no clickable request/JSON-path detail).
     * [statementAccountId] is the account whose feed the record is (see [Source.statementAccountId]), which the engine
     * sets from the transfer's `statementAccount`.
     */
    data class Api(
        val sessionId: ApiSessionId,
        val requestId: ApiRequestId? = null,
        val jsonPath: JsonPath? = null,
        val statementAccountId: AccountId? = null,
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
        Source.Reconciliation -> SourceType.RECONCILIATION
        is Source.Csv -> SourceType.CSV_IMPORT
        is Source.Api -> SourceType.API
    }

/**
 * The account whose statement (or API feed) an imported record is: its own account, before any direction flip. An
 * importer sets it on the transactions it writes, and it is recorded with their provenance (never read back into a
 * [Source]): reconciliation reads it to tell which end of another source's record is that record's own account, and
 * two of one account's statement lines from two accounts' records of one movement.
 */
val Source.statementAccountId: AccountId?
    get() =
        when (this) {
            is Source.Csv -> statementAccountId
            is Source.Api -> statementAccountId
            else -> null
        }

/** This source with [account] as its [statementAccountId]; only an import's sources carry one. */
fun Source.withStatementAccount(account: AccountId): Source =
    when (this) {
        is Source.Csv -> copy(statementAccountId = account)
        is Source.Api -> copy(statementAccountId = account)
        else -> this
    }
