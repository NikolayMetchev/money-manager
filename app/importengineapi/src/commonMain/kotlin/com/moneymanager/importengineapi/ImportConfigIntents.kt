package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.model.csv.CsvRowLink
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.regex.PortableRegex
import kotlin.time.Instant

/*
 * Intents for the import-configuration and staging tables (strategies, account mappings, CSV
 * staging rows, settings, device). The engine applies these so callers never hold the corresponding
 * write repository. Create variants carry a String `key` whose generated id is read back from the
 * matching map on ImportResult.
 */

/** A write on the pass-through (conduit) account config table (e.g. Curve definitions). */
sealed interface PassThroughMutation {
    data class Create(
        val account: PassThroughAccount,
    ) : PassThroughMutation

    data class Update(
        val account: PassThroughAccount,
    ) : PassThroughMutation

    data class Delete(
        val id: PassThroughAccountId,
    ) : PassThroughMutation
}

/** A write on the locations' holiday calendars, keyed by code ([HolidayCalendar.id]). */
sealed interface HolidayCalendarMutation {
    /** Creates the calendar, or replaces the one with its code. */
    data class Upsert(
        val calendar: HolidayCalendar,
    ) : HolidayCalendarMutation

    data class Delete(
        val code: String,
    ) : HolidayCalendarMutation
}

/**
 * A person's edit of one attribute on an existing trade. The engine records it as a MANUAL revision of the
 * trade, so a re-import of the trade's source puts it back on the re-created trade.
 */
sealed interface TradeAttributeMutation {
    val tradeId: TradeId
    val typeId: AttributeTypeId

    data class Set(
        override val tradeId: TradeId,
        override val typeId: AttributeTypeId,
        val value: String,
    ) : TradeAttributeMutation

    data class Remove(
        override val tradeId: TradeId,
        override val typeId: AttributeTypeId,
    ) : TradeAttributeMutation
}

/** A write of a relationship between existing transactions, outside any one transfer's import. */
sealed interface RelationshipMutation {
    /**
     * A relationship of [typeId] over [members], recorded with [source] as its provenance (a person's is
     * [Source.Manual], which a re-import of any member's source rebuilds on the re-created transactions).
     * Skipped when [typeId] already relates exactly these members.
     */
    data class Create(
        val typeId: RelationshipTypeId,
        val members: List<RelationshipMember>,
        val source: Source,
    ) : RelationshipMutation

    data class Delete(
        val id: RelationshipId,
    ) : RelationshipMutation
}

/** A write on the shadow→real reconciliation account links. */
sealed interface ReconciliationLinkMutation {
    /**
     * Replaces every link of [shadowAccountId] with links to [realAccountIds] (empty = unlink it). The
     * engine rejects a non-shadow [shadowAccountId], a shadow account among [realAccountIds], and a real
     * account already linked to another shadow account of the same source.
     */
    data class SetLinks(
        val shadowAccountId: AccountId,
        val realAccountIds: Set<AccountId>,
    ) : ReconciliationLinkMutation
}

/** A write on the CSV import-strategy table. */
sealed interface CsvStrategyMutation {
    data class Create(
        val key: String,
        val strategy: CsvImportStrategy,
        val source: Source,
    ) : CsvStrategyMutation

    data class Update(
        val strategy: CsvImportStrategy,
        val source: Source,
    ) : CsvStrategyMutation

    data class Delete(
        val id: CsvImportStrategyId,
    ) : CsvStrategyMutation
}

/** A write on the API import-strategy table. */
sealed interface ApiStrategyMutation {
    data class Create(
        val key: String,
        val strategy: ApiImportStrategy,
        val source: Source,
    ) : ApiStrategyMutation

    data class Update(
        val strategy: ApiImportStrategy,
        val source: Source,
    ) : ApiStrategyMutation

    data class Delete(
        val id: ApiImportStrategyId,
    ) : ApiStrategyMutation
}

/** A write on the account-mapping table (strategyId null = global, else scoped to that strategy). */
sealed interface AccountMappingMutation {
    data class Create(
        val key: String,
        val valuePattern: PortableRegex,
        val accountId: AccountId,
        val strategyId: CsvImportStrategyId? = null,
    ) : AccountMappingMutation

    data class CreateBatch(
        val mappings: List<AccountMapping>,
    ) : AccountMappingMutation

    data class Update(
        val mapping: AccountMapping,
    ) : AccountMappingMutation

    data class Delete(
        val id: Long,
    ) : AccountMappingMutation
}

/** A write on the CSV staging tables (the stored file + per-row status write-back). */
sealed interface CsvImportMutation {
    data class Create(
        val key: String,
        val fileName: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val fileChecksum: String,
        val fileLastModified: Instant,
        // Non-null only for an Excel import: the raw workbook bytes + the worksheet [headers]/[rows]
        // were parsed from, stored alongside the staged rows so the sheet can be re-extracted if a
        // different worksheet is later selected.
        val xlsxBytes: ByteArray? = null,
        val xlsxWorksheetName: String? = null,
        // Non-null only for a PDF import: the file and the text lines [rows] were staged from.
        val pdf: PdfImportSource? = null,
    ) : CsvImportMutation {
        // ByteArray uses reference equality by default; override with structural (contentEquals)
        // comparison so this mutation compares correctly in tests/state diffing.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is Create &&
                        key == other.key &&
                        fileName == other.fileName &&
                        headers == other.headers &&
                        rows == other.rows &&
                        fileChecksum == other.fileChecksum &&
                        fileLastModified == other.fileLastModified &&
                        xlsxBytes.contentEquals(other.xlsxBytes) &&
                        xlsxWorksheetName == other.xlsxWorksheetName &&
                        pdf == other.pdf
                )

        override fun hashCode(): Int {
            var result = key.hashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + headers.hashCode()
            result = 31 * result + rows.hashCode()
            result = 31 * result + fileChecksum.hashCode()
            result = 31 * result + fileLastModified.hashCode()
            result = 31 * result + (xlsxBytes?.contentHashCode() ?: 0)
            result = 31 * result + xlsxWorksheetName.hashCode()
            result = 31 * result + pdf.hashCode()
            return result
        }
    }

    data class Delete(
        val id: CsvImportId,
    ) : CsvImportMutation

    /**
     * Replaces an already-staged import's rows/columns in place (keeping its id): an Excel import's with a
     * different worksheet re-parsed from the stored workbook bytes, when the matched strategy names a sheet
     * other than the one initially staged (the first sheet); a PDF import's with the rows its strategy's line
     * rules assembled from the stored lines ([pdfRulesHash] names those rules, null for the raw lines).
     */
    data class Restage(
        val id: CsvImportId,
        val headers: List<String>,
        val rows: List<List<String>>,
        val worksheetName: String? = null,
        val pdfRulesHash: String? = null,
    ) : CsvImportMutation

    /**
     * Replaces a staged import's contents in place with a fresh parse of its re-downloaded file, keeping
     * its id, history and folder link; see `CsvImportWriteRepository.repopulateImport`. [carriedRows] and
     * [rowIndexRemap] carry an already-imported file's row outcomes and provenance onto the new rows.
     */
    class Repopulate(
        val id: CsvImportId,
        val headers: List<String>,
        val rows: List<List<String>>,
        val fileChecksum: String,
        val fileLastModified: Instant,
        val carriedRows: List<CsvRowLink> = emptyList(),
        val rowIndexRemap: Map<Long, Long> = emptyMap(),
        val xlsxBytes: ByteArray? = null,
        val pdf: PdfImportSource? = null,
    ) : CsvImportMutation

    /** Toggles the "ignored" flag; ignored files are hidden from the actionable lists. */
    data class SetIgnored(
        val id: CsvImportId,
        val ignored: Boolean,
    ) : CsvImportMutation

    /**
     * Returns the file to the never-applied state (rows reset, errors and applications cleared) after
     * an unimport deleted the transactions it created.
     */
    data class ResetToUnimported(
        val id: CsvImportId,
    ) : CsvImportMutation

    data class UpdateRowStatuses(
        val id: CsvImportId,
        val status: String,
        val rowTransferMap: Map<Long, TransferId?>,
    ) : CsvImportMutation

    /**
     * Clears the rows' import status + transfer link so a re-import re-runs them from scratch (used
     * after their previously-imported transfers were deleted). Null [rowIndexes] resets every row.
     */
    data class ResetRowStatuses(
        val id: CsvImportId,
        val rowIndexes: Collection<Long>?,
    ) : CsvImportMutation

    /** Saves each row's error message ([errors]: row index to message), replacing any it had. */
    data class SaveErrors(
        val id: CsvImportId,
        val errors: Map<Long, String>,
    ) : CsvImportMutation

    data class ClearError(
        val id: CsvImportId,
        val rowIndex: Long,
    ) : CsvImportMutation

    data class ClearErrors(
        val id: CsvImportId,
        val rowIndexes: Collection<Long>,
    ) : CsvImportMutation

    data class RecordApplication(
        val id: CsvImportId,
        val strategyId: CsvImportStrategyId,
        val strategyName: String,
        val appliedAt: Instant,
        /** The source account the run used, so a re-import uses it again; null when the strategy reads it per row. */
        val accountId: AccountId? = null,
        /** The currency the run used, so a re-import uses it again; null when the strategy reads it per row. */
        val currencyId: CurrencyId? = null,
    ) : CsvImportMutation
}

/** A write on the import-directory config + its per-file change-detection cursor. */
sealed interface ImportDirectoryMutation {
    data class Create(
        val key: String,
        val directory: ImportDirectory,
        val source: Source,
    ) : ImportDirectoryMutation

    data class Update(
        val directory: ImportDirectory,
        val source: Source,
    ) : ImportDirectoryMutation

    data class Delete(
        val id: ImportDirectoryId,
    ) : ImportDirectoryMutation

    /** Advance the cursor for a file just imported from the directory. */
    data class RecordFileImported(
        val directoryId: ImportDirectoryId,
        val fileRef: String,
        val fileName: String,
        val lastModified: Instant,
        val checksum: String?,
        /** Server-provided content hash (e.g. Drive md5Checksum) used to skip re-downloading; null for local. */
        val remoteContentHash: String?,
        val csvImportId: CsvImportId?,
        val importedAt: Instant,
    ) : ImportDirectoryMutation
}

/** Settings writes; non-null fields are applied. */
data class ImportSettings(
    val defaultCurrencyId: CurrencyId? = null,
    val defaultLocation: DefaultLocation? = null,
    val setupWizardCompleted: Boolean? = null,
)

/** The holiday calendar [code] accounts without their own location settle on; null clears it (weekends only). */
data class DefaultLocation(
    val code: String?,
)
