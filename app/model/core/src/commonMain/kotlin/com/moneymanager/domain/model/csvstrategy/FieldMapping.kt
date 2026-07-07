@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class FieldMappingId(
    @Contextual val id: Uuid,
) {
    override fun toString() = id.toString()
}

/**
 * Base interface for all field mapping types.
 * Each mapping defines how a Transfer field is populated from CSV data.
 */
@Serializable
sealed interface FieldMapping {
    val id: FieldMappingId
    val fieldType: TransferField
}

/**
 * Always uses a specific account, regardless of CSV data.
 * Typically used for the source account (the account the CSV statement belongs to).
 */
@Serializable
data class HardCodedAccountMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val accountId: AccountId,
) : FieldMapping

/**
 * Looks up an account by name from a CSV column.
 *
 * When [fallbackColumns] is specified, if the primary [columnName] is empty,
 * each fallback column is tried in order until a non-empty value is found.
 * This is useful for bank exports where some transaction types (e.g., cheques)
 * have empty name columns but the transaction type can be used as a fallback.
 */
@Serializable
data class AccountLookupMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
    val fallbackColumns: List<String> = emptyList(),
    val defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
) : FieldMapping {
    /**
     * Returns all columns to check in priority order (primary first, then fallbacks).
     */
    val allColumns: List<String>
        get() = listOf(columnName) + fallbackColumns
}

/**
 * A reusable regex extraction: a [pattern] matched (case-insensitively) against a column value, and
 * an [outputTemplate] producing the result from the match. Templates support `$0` (the whole match),
 * `$1`..`$9` (numbered groups) and `${name}` (named groups). When the pattern does not match the
 * caller decides the fallback. Used by regex account rules, attribute mappings and the description
 * mapping so capture-group extraction lives in one place.
 */
@Serializable
data class ColumnExtraction(
    val pattern: String,
    val outputTemplate: String = "$0",
)

/**
 * A single regex rule that maps matched values to an account name.
 *
 * When [accountNameTemplate] is null the fixed [accountName] is used (the original behaviour). When
 * set, the matched value is run through capture-group substitution (see [ColumnExtraction]) to derive
 * the account name from the matched text — e.g. pattern `CARD PAYMENT TO (?<cp>.+?),` with template
 * `${cp}` extracts the counterparty. [counterpartyIsPerson] marks the resolved counterparty as a
 * person, so the import additionally creates a Person + ownership link rather than just an account.
 */
@Serializable
data class RegexRule(
    val pattern: String,
    val accountName: String,
    val accountNameTemplate: String? = null,
    val counterpartyIsPerson: Boolean = false,
)

/**
 * Maps CSV column values to accounts using regex pattern matching.
 * Rules are evaluated in order; first match wins.
 * If no rules match, uses the raw column value for account lookup.
 * All matching is case-insensitive.
 *
 * When [fallbackColumns] is specified and no regex rules match, if the primary
 * [columnName] is empty, each fallback column is tried in order until a non-empty
 * value is found.
 */
@Serializable
data class RegexAccountMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
    val rules: List<RegexRule>,
    val fallbackColumns: List<String> = emptyList(),
    val defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
) : FieldMapping {
    /**
     * Returns all columns to check in priority order (primary first, then fallbacks).
     */
    val allColumns: List<String>
        get() = listOf(columnName) + fallbackColumns
}

/**
 * Looks up an account by templating a CSV column value into an account name.
 * The looked-up name is `prefix + columnValue + suffix` (e.g. "Wise: " + "EUR").
 * Useful when one logical account exists per currency and the CSV only carries
 * the currency code.
 *
 * Persisted [CsvAccountMapping] overrides on the raw column value are applied first,
 * so renamed accounts keep matching.
 */
@Serializable
data class TemplateAccountMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
    val prefix: String = "",
    val suffix: String = "",
    val defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
) : FieldMapping

/**
 * Chooses between two account mappings based on row-level conditions.
 * All [conditions] must hold (AND) for [whenTrue] to be used; otherwise [whenFalse] applies.
 * Conditions are evaluated against the row values after any row preprocessing rules ran.
 */
@Serializable
data class ConditionalAccountMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val conditions: List<RowCondition>,
    val whenTrue: FieldMapping,
    val whenFalse: FieldMapping,
) : FieldMapping

/**
 * Parses a date/time from one or two CSV columns.
 * If only a date column is specified, uses the defaultTime.
 *
 * When [dateTimeFormat] is set, the [dateColumnName] column holds a combined
 * date+time value parsed with that format, and the other time settings are ignored.
 */
@Serializable
data class DateTimeParsingMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val dateColumnName: String,
    val dateFormat: String,
    val timeColumnName: String? = null,
    val timeFormat: String? = null,
    val defaultTime: String = "12:00:00",
    val dateTimeFormat: String? = null,
) : FieldMapping

/**
 * Directly copies a string value from a CSV column.
 * Used for the description field.
 *
 * When [fallbackColumns] is specified, if the primary [columnName] is empty,
 * each fallback column is tried in order until a non-empty value is found.
 *
 * When [extraction] is set, the resolved column value is run through it to derive a cleaned
 * description (e.g. stripping a trailing amount); if the pattern does not match, the raw value is
 * kept so nothing is lost.
 */
@Serializable
data class DirectColumnMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
    val fallbackColumns: List<String> = emptyList(),
    val extraction: ColumnExtraction? = null,
) : FieldMapping {
    /**
     * Returns all columns to check in priority order (primary first, then fallbacks).
     */
    val allColumns: List<String>
        get() = listOf(columnName) + fallbackColumns
}

/**
 * Parses a numeric amount from CSV columns.
 * Supports two modes: single column with +/- values, or separate credit/debit columns.
 *
 * When flipAccountsOnPositive is true and the parsed amount is positive,
 * the source and target accounts are swapped. This is useful for bank statements
 * where positive values indicate money flowing INTO the statement account.
 *
 * When [feeColumnName] is set, that column's value (if non-blank) is imported as its own fee
 * transfer linked to the main transaction (via a `fee` relationship), whenever all [feeConditions]
 * hold (empty = always). This handles exports like Wise's, where the amount column is net of fees but
 * the fee also left the account (e.g. ATM withdrawals: 200.00 withdrawn + a 7.29 fee movement).
 */
@Serializable
data class AmountParsingMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val mode: AmountMode,
    val amountColumnName: String? = null,
    val creditColumnName: String? = null,
    val debitColumnName: String? = null,
    val negateValues: Boolean = false,
    val flipAccountsOnPositive: Boolean = false,
    val feeColumnName: String? = null,
    val feeConditions: List<RowCondition> = emptyList(),
) : FieldMapping {
    init {
        when (mode) {
            AmountMode.SINGLE_COLUMN ->
                requireNotNull(amountColumnName) {
                    "amountColumnName is required for SINGLE_COLUMN mode"
                }
            AmountMode.CREDIT_DEBIT_COLUMNS -> {
                requireNotNull(creditColumnName) {
                    "creditColumnName is required for CREDIT_DEBIT_COLUMNS mode"
                }
                requireNotNull(debitColumnName) {
                    "debitColumnName is required for CREDIT_DEBIT_COLUMNS mode"
                }
            }
        }
    }
}

/**
 * Always uses a specific currency, regardless of CSV data.
 */
@Serializable
data class HardCodedCurrencyMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val currencyId: CurrencyId,
) : FieldMapping

/**
 * Looks up a currency by code from a CSV column.
 * The column should contain ISO 4217 currency codes (e.g., "GBP", "USD", "EUR").
 */
@Serializable
data class CurrencyLookupMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
    /**
     * When true, a value in [columnName] that is not a known fiat currency is treated as a crypto asset
     * and created on demand (scale derived from the paired amount column). Set on the crypto.com
     * strategies, whose currency columns only ever carry fiat or crypto tickers. Default false keeps the
     * safe registry-only behaviour for other banks (a stray/typo code stays fiat, not a bogus crypto).
     */
    val treatNonFiatAsCrypto: Boolean = false,
) : FieldMapping

/**
 * Always uses a specific timezone, regardless of CSV data.
 * The timezoneId should be a valid IANA timezone ID (e.g., "Europe/London", "UTC").
 */
@Serializable
data class HardCodedTimezoneMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val timezoneId: String,
) : FieldMapping

/**
 * Looks up a timezone by IANA timezone ID from a CSV column.
 * The column should contain valid timezone IDs (e.g., "Europe/London", "America/New_York").
 */
@Serializable
data class TimezoneLookupMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
) : FieldMapping
