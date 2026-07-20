@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.SortedAccountMappingListSerializer
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.ColumnExtraction
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.SortedCompanionTransactionRuleListSerializer
import com.moneymanager.domain.model.csvstrategy.SortedContentMatchRuleListSerializer
import com.moneymanager.domain.model.csvstrategy.SortedRowConditionListSerializer
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import kotlinx.serialization.EncodeDefault
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
 * @property rowPreprocessingRules Row preprocessing rules (already portable, no IDs)
 * @property companionTransactionRules Companion transaction rules (already portable, no IDs)
 * @property accountMappings This strategy's own per-strategy account mappings (by account name),
 * so they travel with the strategy. Global mappings are exported separately.
 * @property fileNamePattern Optional regex matched against the imported file's original name
 * (see [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.fileNamePattern])
 * @property crossSourceReconcileWindowSeconds Cross-source reconciliation window
 * (see [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.crossSourceReconcileWindowSeconds])
 * @property conversionConfig Asset-conversion configuration (already portable, no IDs)
 * (see [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.conversionConfig])
 * @property fundingAttributeMatch Attribute-based funding-account match (already portable, no IDs)
 * (see [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.fundingAttributeMatch])
 * @property worksheetName When set, this is an Excel strategy targeting this worksheet
 * (see [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy.worksheetName])
 */
@Serializable
data class CsvStrategyExport(
    val version: String,
    val name: String,
    // Order-insensitive collections use canonical (sorted) serializers so the same strategy always
    // serializes to identical bytes on every device; a few lists below have semantic order (see their
    // own comments) and keep default insertion-order serialization instead.
    @Serializable(with = SortedStringSetSerializer::class)
    val identificationColumns: Set<String>,
    @Serializable(with = SortedFieldMappingsSerializer::class)
    val fieldMappings: Map<TransferField, FieldMappingExport>,
    @Serializable(with = SortedAttributeMappingListSerializer::class)
    val attributeMappings: List<AttributeColumnMapping> = emptyList(),
    // Rules apply sequentially and each can affect what the next rule's conditions see (not
    // first-match) - order is semantic, keeps default insertion-order serialization.
    val rowPreprocessingRules: List<RowPreprocessingRule> = emptyList(),
    @Serializable(with = SortedCompanionTransactionRuleListSerializer::class)
    val companionTransactionRules: List<CompanionTransactionRule> = emptyList(),
    @Serializable(with = SortedContentMatchRuleListSerializer::class)
    val contentMatchRules: List<ContentMatchRule> = emptyList(),
    @Serializable(with = SortedAccountMappingListSerializer::class)
    val accountMappings: List<AccountMappingExport> = emptyList(),
    val fileNamePattern: String? = null,
    val crossSourceReconcileWindowSeconds: Long? = null,
    val conversionConfig: ConversionConfig? = null,
    // Omitted from JSON when null (unlike the fields above, which encode their null under the codec's
    // encodeDefaults=true) so ADDING this field does not change the canonical hash of every existing
    // strategy — only a strategy that actually sets it (Curve) rehashes. Prevents a spurious "all
    // strategies changed" on catalog/Drive sync. See StrategyArtifactCodec.canonicalHash.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fundingAttributeMatch: AttributeAccountMatch? = null,
    // Same NEVER-encode rationale as fundingAttributeMatch above: only strategies that actually set a
    // worksheet name (XLSX strategies) rehash when this field is added.
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
