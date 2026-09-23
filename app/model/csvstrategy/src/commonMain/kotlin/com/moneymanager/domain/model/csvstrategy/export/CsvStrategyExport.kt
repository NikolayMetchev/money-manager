package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.SortedAccountMappingListSerializer
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.ColumnExtraction
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.SortedRowConditionListSerializer
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Portable export format for CSV import strategies: a [name] plus its [CsvStrategyConfig], with the
 * field mappings' database ids replaced by names for cross-device portability.
 *
 * @property version App version that created this export (for compatibility tracking)
 * @property name Strategy name
 * @property config The strategy's configuration, field mappings in export (by-name) form
 * @property accountMappings This strategy's own per-strategy account mappings (by account name),
 * so they travel with the strategy. Global mappings are exported separately.
 * @property worksheetName When set, this is an Excel strategy targeting this worksheet
 * (see [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.worksheetName])
 */
@Serializable
data class CsvStrategyExport(
    val version: String,
    val name: String,
    val config: CsvStrategyConfig<FieldMappingExport>,
    @Serializable(with = SortedAccountMappingListSerializer::class)
    val accountMappings: List<AccountMappingExport> = emptyList(),
    // Omitted from JSON when null so adding it only rehashed the strategies that set it (XLSX ones);
    // see CsvStrategyConfig.fundingAttributeMatch for the full rationale.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val worksheetName: String? = null,
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
    // Tried in order until one yields a non-blank value - order is semantic, keeps default
    // insertion-order serialization.
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
    // First-match-wins - order is semantic, keeps default insertion-order serialization.
    val rules: List<RegexRule>,
    // Tried in order until one yields a non-blank value - order is semantic, keeps default
    // insertion-order serialization.
    val fallbackColumns: List<String> = emptyList(),
    val defaultCategoryName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.AttributeMatchAccountMapping].
 * Uses category name instead of category ID; columnName + attributeTypeName are already portable.
 */
@Serializable
data class AttributeMatchAccountExport(
    override val fieldType: TransferField,
    val columnName: String,
    val attributeTypeName: String,
    val defaultCategoryName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping].
 * Uses category name instead of category ID.
 */
@Serializable
data class TemplateAccountExport(
    override val fieldType: TransferField,
    val columnName: String,
    val prefix: String = "",
    val suffix: String = "",
    val defaultCategoryName: String,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping].
 * RowCondition is already portable (no IDs).
 */
@Serializable
data class ConditionalAccountExport(
    override val fieldType: TransferField,
    @Serializable(with = SortedRowConditionListSerializer::class)
    val conditions: List<RowCondition>,
    val whenTrue: FieldMappingExport,
    val whenFalse: FieldMappingExport,
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
    val dateTimeFormat: String? = null,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.DirectColumnMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class DirectColumnExport(
    override val fieldType: TransferField,
    val columnName: String,
    // Tried in order until one yields a non-blank value - order is semantic, keeps default
    // insertion-order serialization.
    val fallbackColumns: List<String> = emptyList(),
    val extraction: ColumnExtraction? = null,
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
    val feeColumnName: String? = null,
    @Serializable(with = SortedRowConditionListSerializer::class)
    val feeConditions: List<RowCondition> = emptyList(),
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
