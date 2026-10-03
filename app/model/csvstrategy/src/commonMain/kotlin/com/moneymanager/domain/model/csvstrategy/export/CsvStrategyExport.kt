package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.SortedAccountMappingListSerializer
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
import com.moneymanager.domain.model.rules.ValueExpr
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
 * Export format for [com.moneymanager.domain.model.csvstrategy.AccountRulesMapping].
 * Uses category name instead of category ID; the rules themselves are already portable.
 */
@Serializable
data class AccountRulesExport(
    override val fieldType: TransferField,
    // First applicable rule wins - order is semantic, keeps default insertion-order serialization.
    val rules: List<AccountRule>,
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
    val dateTimeFormat: String? = null,
) : FieldMappingExport

/**
 * Export format for [com.moneymanager.domain.model.csvstrategy.DirectColumnMapping].
 * No IDs - fully portable as-is.
 */
@Serializable
data class DirectColumnExport(
    override val fieldType: TransferField,
    val value: ValueExpr,
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
    val direction: Direction = Direction.Outgoing,
    val feeColumnName: String? = null,
    @Serializable(with = SortedConditionListSerializer::class)
    val feeConditions: List<Condition> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val feeCurrency: ValueExpr? = null,
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
    val value: ValueExpr,
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
