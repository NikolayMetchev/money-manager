package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlinx.serialization.Serializable

/**
 * Portable export format for CSV import strategies.
 * Uses names instead of database IDs for cross-device portability.
 *
 * @property version App version that created this export (for compatibility tracking)
 * @property name Strategy name
 * @property identificationColumns Columns used to auto-identify this strategy
 * @property fieldMappings Map of transfer fields to their export-format mappings
 * @property attributeMappings Attribute column mappings (already portable, no IDs)
 */
@Serializable
data class CsvStrategyExport(
    val version: String,
    val name: String,
    val identificationColumns: Set<String>,
    val fieldMappings: Map<TransferField, FieldMappingExport>,
    val attributeMappings: List<AttributeColumnMapping> = emptyList(),
)

/**
 * Base interface for portable field mapping exports.
 * Unlike [com.moneymanager.domain.model.csvstrategy.FieldMapping], these use
 * names instead of database IDs for portability.
 */
@Serializable
sealed interface FieldMappingExport {
    val fieldType: TransferField
}

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping].
 * Uses account name instead of account ID.
 */
@Serializable
data class HardCodedAccountExport(
    override val fieldType: TransferField,
    val accountName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.AccountLookupMapping].
 * Uses category name instead of category ID.
 */
@Serializable
data class AccountLookupExport(
    override val fieldType: TransferField,
    val columnName: String,
    val fallbackColumns: List<String> = emptyList(),
    val defaultCategoryName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.RegexAccountMapping].
 * Uses category name instead of category ID.
 * RegexRule is already portable (uses account name strings).
 */
@Serializable
data class RegexAccountExport(
    override val fieldType: TransferField,
    val columnName: String,
    val rules: List<RegexRule>,
    val fallbackColumns: List<String> = emptyList(),
    val defaultCategoryName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class DateTimeParsingExport(
    override val fieldType: TransferField,
    val dateColumnName: String,
    val dateFormat: String,
    val timeColumnName: String? = null,
    val timeFormat: String? = null,
    val defaultTime: String = "12:00:00",
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.DirectColumnMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class DirectColumnExport(
    override val fieldType: TransferField,
    val columnName: String,
    val fallbackColumns: List<String> = emptyList(),
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.AmountParsingMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class AmountParsingExport(
    override val fieldType: TransferField,
    val mode: AmountMode,
    val amountColumnName: String? = null,
    val creditColumnName: String? = null,
    val debitColumnName: String? = null,
    val negateValues: Boolean = false,
    val flipAccountsOnPositive: Boolean = false,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping].
 * Uses ISO 4217 currency code instead of currency ID.
 */
@Serializable
data class HardCodedCurrencyExport(
    override val fieldType: TransferField,
    val currencyCode: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class CurrencyLookupExport(
    override val fieldType: TransferField,
    val columnName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping].
 * No IDs - fully portable as-is (timezoneId is already a string).
 */
@Serializable
data class HardCodedTimezoneExport(
    override val fieldType: TransferField,
    val timezoneId: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class TimezoneLookupExport(
    override val fieldType: TransferField,
    val columnName: String,
) : FieldMappingExport
