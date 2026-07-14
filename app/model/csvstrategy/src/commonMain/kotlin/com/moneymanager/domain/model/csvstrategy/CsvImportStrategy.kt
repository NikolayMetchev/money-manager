@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.CsvImportStrategyId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A content-based match rule used to auto-detect a strategy from the data itself, for sources whose
 * column set is fixed and therefore cannot distinguish formats (e.g. QIF, where every file has the
 * same columns). A strategy is a content match for a file when a sampled row has a value in
 * [columnName] matching [pattern] (case-insensitively). A strategy with no content rules never
 * positively content-matches and so acts as the fallback.
 */
@Serializable
data class ContentMatchRule(
    val columnName: String,
    val pattern: String,
)

/**
 * Represents a reusable CSV import strategy that defines how to map CSV columns
 * to Transfer fields.
 *
 * @property id Unique identifier for this strategy
 * @property name Human-readable name for the strategy (must be unique)
 * @property identificationColumns Set of column names used to auto-identify this strategy
 *                                 when importing a CSV file. Matching is exact and order-independent.
 * @property fieldMappings Map of TransferField to FieldMapping defining how each field is populated
 * @property rowPreprocessingRules Rules that may swap column values / flip accounts per row
 *                                 before field mappings run (see [RowPreprocessingRule])
 * @property companionTransactionRules Rules flagging imported transfers that require a manually
 *                                     entered companion transaction (see [CompanionTransactionRule])
 * @property contentMatchRules Rules that auto-detect this strategy from row content when the column
 *                             set is fixed and cannot distinguish formats (see [ContentMatchRule]).
 * @property fileNamePattern Optional regex matched (case-insensitively, anywhere) against the
 *                           imported file's original name. The strongest selection signal for
 *                           sources whose exports share a column set but differ by filename
 *                           (e.g. crypto.com's card_/fiat_/crypto_transactions_record files).
 * @property crossSourceReconcileWindowSeconds When set, a row that fuzzy-matches an existing
 *                                             transfer from a different source (same accounts and
 *                                             amount, timestamps within this window) is imported
 *                                             but tagged excluded and linked as reconciled instead
 *                                             of counting twice. Null disables reconciliation.
 * @property conversionConfig When set, describes how this source expresses asset conversions as
 *                            separate debited/credited rows; the importer routes the legs through a
 *                            shared counterparty account and links each debit to its credit (see
 *                            [ConversionConfig]). Null when the source has no such conversions.
 * @property createdAt Timestamp when this strategy was created
 * @property updatedAt Timestamp when this strategy was last modified
 */
data class CsvImportStrategy(
    val id: CsvImportStrategyId,
    val name: String,
    val identificationColumns: Set<String>,
    val fieldMappings: Map<TransferField, FieldMapping>,
    val attributeMappings: List<AttributeColumnMapping> = emptyList(),
    val rowPreprocessingRules: List<RowPreprocessingRule> = emptyList(),
    val companionTransactionRules: List<CompanionTransactionRule> = emptyList(),
    val contentMatchRules: List<ContentMatchRule> = emptyList(),
    val fileNamePattern: String? = null,
    val crossSourceReconcileWindowSeconds: Long? = null,
    val conversionConfig: ConversionConfig? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Returns true if the given CSV column headings match this strategy's identification columns.
     * Matching is exact and order-independent.
     */
    fun matchesColumns(csvHeadings: Set<String>): Boolean = identificationColumns == csvHeadings
}
