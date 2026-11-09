package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.CsvImportStrategyId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Selects an account by matching a CSV [column]'s value against the regex patterns held by a given
 * account-attribute type. Each account carries an attribute of [attributeTypeName] whose value is one
 * or more whitespace/comma-separated regex tokens (e.g. the `card-last4` attribute lists a card's
 * last-4 digits — a trivial regex — but any pattern is allowed; use `\s` for a literal space). A
 * column value is matched (case-insensitively, [Regex.containsMatchIn]) against every token; when
 * exactly one account matches it wins, and a value claimed by more than one account is ambiguous and
 * ignored. Fully portable across databases: it names a column and an attribute type, never an account id.
 */
@Serializable
data class AttributeAccountMatch(
    val column: String,
    val attributeTypeName: String,
)

/**
 * Represents a reusable CSV import strategy that defines how to map CSV columns
 * to Transfer fields.
 *
 * @property id Unique identifier for this strategy
 * @property name Human-readable name for the strategy (must be unique)
 * @property config Everything the strategy configures (see [CsvStrategyConfig])
 * @property worksheetName When set, this strategy targets an Excel worksheet by this name rather than a
 *                          CSV file (like QIF, an XLSX strategy is an ordinary [CsvImportStrategy] that
 *                          rides the CSV engine — see [isXlsxStrategy]). The named worksheet's first row
 *                          is treated as headers and the rest as data, exactly like a CSV. Null for CSV
 *                          and QIF strategies. Kept out of [config]: it is a kind discriminator, stored in
 *                          its own `xlsx_import_strategy` table.
 * @property createdAt Timestamp when this strategy was created
 * @property updatedAt Timestamp when this strategy was last modified
 */
data class CsvImportStrategy(
    val id: CsvImportStrategyId,
    val name: String,
    val config: CsvStrategyConfig<FieldMapping>,
    val worksheetName: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Returns true if the given CSV column headings are this strategy's identification columns, plus any of its
     * optional columns. Matching is order-independent; with no optional columns it is exact.
     */
    fun matchesColumns(csvHeadings: Set<String>): Boolean =
        csvHeadings.containsAll(config.identificationColumns) &&
            (config.identificationColumns + config.optionalColumns).containsAll(csvHeadings)

    /**
     * Whether this version of a strategy still sets everything [previous] set of a file's import settings: the source
     * account and the currency. A file imported under a strategy that reads one from its rows records none of its own, so
     * a version that stopped setting it would leave the file without one, and its re-import would have nothing to run
     * with. No version may stop, as the files a strategy imported are what a re-import runs.
     */
    fun keepsImportSettingsOf(previous: CsvImportStrategy): Boolean =
        IMPORT_SETTINGS.all { previous.config.fieldMappings[it] == null || config.fieldMappings[it] != null }

    private companion object {
        val IMPORT_SETTINGS = listOf(TransferField.SOURCE_ACCOUNT, TransferField.CURRENCY)
    }
}
