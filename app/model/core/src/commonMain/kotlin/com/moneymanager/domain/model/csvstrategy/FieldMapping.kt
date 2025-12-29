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
 * Can optionally create a new account if not found.
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
    val createIfMissing: Boolean = true,
    val defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
) : FieldMapping {
    /**
     * Returns all columns to check in priority order (primary first, then fallbacks).
     */
    val allColumns: List<String>
        get() = listOf(columnName) + fallbackColumns
}

/**
 * Parses a date/time from one or two CSV columns.
 * If only a date column is specified, uses the defaultTime.
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
) : FieldMapping

/**
 * Directly copies a string value from a CSV column.
 * Used for the description field.
 */
@Serializable
data class DirectColumnMapping(
    override val id: FieldMappingId,
    override val fieldType: TransferField,
    val columnName: String,
) : FieldMapping

/**
 * Parses a numeric amount from CSV columns.
 * Supports two modes: single column with +/- values, or separate credit/debit columns.
 *
 * When flipAccountsOnPositive is true and the parsed amount is positive,
 * the source and target accounts are swapped. This is useful for bank statements
 * where positive values indicate money flowing INTO the statement account.
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
) : FieldMapping {
    init {
        when (mode) {
            AmountMode.SINGLE_COLUMN ->
                require(amountColumnName != null) {
                    "amountColumnName is required for SINGLE_COLUMN mode"
                }
            AmountMode.CREDIT_DEBIT_COLUMNS -> {
                require(creditColumnName != null && debitColumnName != null) {
                    "creditColumnName and debitColumnName are required for CREDIT_DEBIT_COLUMNS mode"
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
