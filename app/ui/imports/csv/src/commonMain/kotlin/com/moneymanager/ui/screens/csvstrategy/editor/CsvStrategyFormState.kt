@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens.csvstrategy.editor

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Currency mapping mode for CSV import.
 */
internal enum class CurrencyMode {
    HARDCODED,
    FROM_COLUMN,
}

/**
 * Timezone mapping mode for CSV import.
 */
internal enum class TimezoneMode {
    HARDCODED,
    FROM_COLUMN,
}

/**
 * Source account mapping mode for CSV import.
 */
internal enum class SourceAccountMode {
    FIXED_ACCOUNT,
    TEMPLATE,
}

/**
 * Target account mapping mode for CSV import.
 */
internal enum class TargetAccountMode {
    DIRECT_LOOKUP,
    REGEX_MATCH,
    TEMPLATE,
    CONDITIONAL,
}

/**
 * The non-conditional account mapping types offered as branches of a conditional
 * mapping. Excluding the conditional kind bounds nesting to a single level.
 */
internal enum class LeafAccountKind {
    LOOKUP,
    REGEX,
    TEMPLATE,
}

internal fun LeafAccountKind.label(): String =
    when (this) {
        LeafAccountKind.LOOKUP -> "Lookup"
        LeafAccountKind.REGEX -> "Regex"
        LeafAccountKind.TEMPLATE -> "Template"
    }

internal fun RowConditionOperator.label(): String =
    when (this) {
        RowConditionOperator.EQUALS_VALUE -> "equals value"
        RowConditionOperator.EQUALS_COLUMN -> "equals column"
        RowConditionOperator.NOT_EQUALS_COLUMN -> "not equals column"
        RowConditionOperator.IS_BLANK -> "is blank"
        RowConditionOperator.IS_NOT_BLANK -> "is not blank"
    }

/**
 * Whether a condition has all the inputs its operator requires.
 */
internal fun RowCondition.isComplete(): Boolean =
    columnName.isNotBlank() &&
        when (operator) {
            RowConditionOperator.EQUALS_VALUE -> !value.isNullOrBlank()
            RowConditionOperator.EQUALS_COLUMN, RowConditionOperator.NOT_EQUALS_COLUMN -> !otherColumnName.isNullOrBlank()
            RowConditionOperator.IS_BLANK, RowConditionOperator.IS_NOT_BLANK -> true
        }

/**
 * Whether a conditional-branch account mapping is fully specified.
 */
internal fun FieldMapping.isLeafAccountValid(): Boolean =
    when (this) {
        is AccountLookupMapping -> columnName.isNotBlank()
        is RegexAccountMapping -> columnName.isNotBlank() && rules.isNotEmpty() && rules.all { it.accountName.isNotBlank() }
        is TemplateAccountMapping -> columnName.isNotBlank()
        is HardCodedAccountMapping -> true
        else -> false
    }

internal fun defaultLeafAccountMapping(
    kind: LeafAccountKind,
    fieldType: TransferField,
    existing: FieldMapping,
): FieldMapping {
    val column =
        when (existing) {
            is AccountLookupMapping -> existing.columnName
            is RegexAccountMapping -> existing.columnName
            is TemplateAccountMapping -> existing.columnName
            else -> ""
        }
    val id = FieldMappingId(Uuid.random())
    return when (kind) {
        LeafAccountKind.LOOKUP -> AccountLookupMapping(id, fieldType, columnName = column)
        LeafAccountKind.REGEX -> RegexAccountMapping(id, fieldType, columnName = column, rules = emptyList())
        LeafAccountKind.TEMPLATE -> TemplateAccountMapping(id, fieldType, columnName = column)
    }
}

internal fun emptyTargetAccountMapping(): FieldMapping =
    AccountLookupMapping(FieldMappingId(Uuid.random()), TransferField.TARGET_ACCOUNT, columnName = "")

internal fun attributeCandidateColumns(
    csvColumns: List<CsvColumn>,
    primaryFieldColumnNames: Set<String?>,
): List<CsvColumn> {
    val usedPrimaryFieldColumns = primaryFieldColumnNames.filterNotNull().toSet()
    return csvColumns.filter { it.originalName !in usedPrimaryFieldColumns }
}

/**
 * Data class holding extracted form state from an existing strategy.
 */
internal data class StrategyFormState(
    val name: String,
    val identificationColumns: Set<String>,
    val dateColumnName: String?,
    val dateFormat: String,
    val timeColumnName: String?,
    val timeFormat: String,
    val descriptionColumnName: String?,
    val descriptionFallbackColumns: List<String>,
    val amountColumnName: String?,
    val flipAccountsOnPositive: Boolean,
    val feeColumnName: String?,
    val feeConditions: List<RowCondition>,
    val dateTimeFormat: String,
    val sourceAccountMode: SourceAccountMode,
    val selectedAccountId: AccountId?,
    val sourceTemplateColumnName: String?,
    val sourceTemplatePrefix: String,
    val sourceTemplateSuffix: String,
    val targetAccountColumnName: String?,
    val targetAccountFallbackColumns: List<String>,
    val targetAccountMode: TargetAccountMode,
    val regexRules: List<RegexRule>,
    val targetTemplateColumnName: String?,
    val targetTemplatePrefix: String,
    val targetTemplateSuffix: String,
    val targetConditions: List<RowCondition>,
    val targetWhenTrue: FieldMapping,
    val targetWhenFalse: FieldMapping,
    val currencyMode: CurrencyMode,
    val selectedCurrencyId: CurrencyId?,
    val currencyColumnName: String?,
    val timezoneMode: TimezoneMode,
    val selectedTimezone: String,
    val timezoneColumnName: String?,
    val attributeMappings: List<AttributeColumnMapping>,
    val rowPreprocessingRules: List<RowPreprocessingRule>,
    val companionTransactionRules: List<CompanionTransactionRule>,
    val contentMatchRules: List<ContentMatchRule>,
    val fileNamePattern: String?,
    val crossSourceReconcileWindowSeconds: Long?,
    val fundingCardColumn: String?,
)

/**
 * Extracts form state from an existing strategy.
 * Returns null for columns that don't exist in the current CSV.
 */
internal fun extractFormStateFromStrategy(
    strategy: CsvImportStrategy,
    availableColumnNames: Set<String>,
): StrategyFormState {
    fun columnIfExists(name: String?): String? = name?.takeIf { it in availableColumnNames }

    // Drop conditions referencing columns absent from the uploaded CSV.
    fun keepConditions(conditions: List<RowCondition>): List<RowCondition> =
        conditions.filter { c ->
            c.columnName in availableColumnNames &&
                (c.otherColumnName == null || c.otherColumnName in availableColumnNames)
        }

    // Clear column references on a conditional branch's leaf mapping when those columns no longer
    // exist, so stale references don't survive extraction and get persisted again on save.
    fun sanitizeLeafMapping(mapping: FieldMapping): FieldMapping =
        when (mapping) {
            is AccountLookupMapping ->
                mapping.copy(
                    columnName = columnIfExists(mapping.columnName).orEmpty(),
                    fallbackColumns = mapping.fallbackColumns.mapNotNull { columnIfExists(it) },
                )
            is RegexAccountMapping ->
                mapping.copy(
                    columnName = columnIfExists(mapping.columnName).orEmpty(),
                    fallbackColumns = mapping.fallbackColumns.mapNotNull { columnIfExists(it) },
                )
            is TemplateAccountMapping ->
                mapping.copy(columnName = columnIfExists(mapping.columnName).orEmpty())
            else -> mapping
        }

    val sourceAccountMapping = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]
    val sourceAccountMode: SourceAccountMode
    val selectedAccountId: AccountId?
    val sourceTemplateColumnName: String?
    val sourceTemplatePrefix: String
    val sourceTemplateSuffix: String
    when (sourceAccountMapping) {
        is TemplateAccountMapping -> {
            sourceAccountMode = SourceAccountMode.TEMPLATE
            selectedAccountId = null
            sourceTemplateColumnName = columnIfExists(sourceAccountMapping.columnName)
            sourceTemplatePrefix = sourceAccountMapping.prefix
            sourceTemplateSuffix = sourceAccountMapping.suffix
        }
        else -> {
            sourceAccountMode = SourceAccountMode.FIXED_ACCOUNT
            selectedAccountId = (sourceAccountMapping as? HardCodedAccountMapping)?.accountId
            sourceTemplateColumnName = null
            sourceTemplatePrefix = ""
            sourceTemplateSuffix = ""
        }
    }

    val targetAccountMapping = strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
    val targetAccountColumnName: String?
    val targetAccountFallbackColumns: List<String>
    val targetAccountMode: TargetAccountMode
    val regexRules: List<RegexRule>
    var targetTemplateColumnName: String? = null
    var targetTemplatePrefix = ""
    var targetTemplateSuffix = ""
    var targetConditions: List<RowCondition> = emptyList()
    var targetWhenTrue: FieldMapping = emptyTargetAccountMapping()
    var targetWhenFalse: FieldMapping = emptyTargetAccountMapping()
    when (targetAccountMapping) {
        is AccountLookupMapping -> {
            targetAccountColumnName = columnIfExists(targetAccountMapping.columnName)
            targetAccountFallbackColumns = targetAccountMapping.fallbackColumns.mapNotNull { columnIfExists(it) }
            targetAccountMode = TargetAccountMode.DIRECT_LOOKUP
            regexRules = emptyList()
        }
        is RegexAccountMapping -> {
            targetAccountColumnName = columnIfExists(targetAccountMapping.columnName)
            targetAccountFallbackColumns = targetAccountMapping.fallbackColumns.mapNotNull { columnIfExists(it) }
            targetAccountMode = TargetAccountMode.REGEX_MATCH
            regexRules = targetAccountMapping.rules
        }
        is TemplateAccountMapping -> {
            targetAccountColumnName = null
            targetAccountFallbackColumns = emptyList()
            targetAccountMode = TargetAccountMode.TEMPLATE
            regexRules = emptyList()
            targetTemplateColumnName = columnIfExists(targetAccountMapping.columnName)
            targetTemplatePrefix = targetAccountMapping.prefix
            targetTemplateSuffix = targetAccountMapping.suffix
        }
        is ConditionalAccountMapping -> {
            targetAccountColumnName = null
            targetAccountFallbackColumns = emptyList()
            targetAccountMode = TargetAccountMode.CONDITIONAL
            regexRules = emptyList()
            targetConditions = keepConditions(targetAccountMapping.conditions)
            targetWhenTrue = sanitizeLeafMapping(targetAccountMapping.whenTrue)
            targetWhenFalse = sanitizeLeafMapping(targetAccountMapping.whenFalse)
        }
        else -> {
            targetAccountColumnName = null
            targetAccountFallbackColumns = emptyList()
            targetAccountMode = TargetAccountMode.DIRECT_LOOKUP
            regexRules = emptyList()
        }
    }

    val timestampMapping = strategy.fieldMappings[TransferField.TIMESTAMP]
    val dateColumnName: String?
    val dateFormat: String
    val timeColumnName: String?
    val timeFormat: String
    val dateTimeFormat: String
    when (timestampMapping) {
        is DateTimeParsingMapping -> {
            dateColumnName = columnIfExists(timestampMapping.dateColumnName)
            dateFormat = timestampMapping.dateFormat
            timeColumnName = columnIfExists(timestampMapping.timeColumnName)
            timeFormat = timestampMapping.timeFormat ?: "HH:mm:ss"
            // Keep the combined format only while its date column still exists.
            dateTimeFormat =
                timestampMapping.dateTimeFormat
                    ?.takeIf { dateColumnName != null }
                    .orEmpty()
        }
        else -> {
            dateColumnName = null
            dateFormat = "dd/MM/yyyy"
            timeColumnName = null
            timeFormat = "HH:mm:ss"
            dateTimeFormat = ""
        }
    }

    val descriptionMapping = strategy.fieldMappings[TransferField.DESCRIPTION]
    val descriptionColumnName: String?
    val descriptionFallbackColumns: List<String>
    when (descriptionMapping) {
        is DirectColumnMapping -> {
            descriptionColumnName = columnIfExists(descriptionMapping.columnName)
            descriptionFallbackColumns = descriptionMapping.fallbackColumns.mapNotNull { columnIfExists(it) }
        }
        else -> {
            descriptionColumnName = null
            descriptionFallbackColumns = emptyList()
        }
    }

    val amountMapping = strategy.fieldMappings[TransferField.AMOUNT]
    val amountColumnName: String?
    val flipAccountsOnPositive: Boolean
    val feeColumnName: String?
    val feeConditions: List<RowCondition>
    when (amountMapping) {
        is AmountParsingMapping -> {
            amountColumnName = columnIfExists(amountMapping.amountColumnName)
            flipAccountsOnPositive = amountMapping.flipAccountsOnPositive
            feeColumnName = columnIfExists(amountMapping.feeColumnName)
            feeConditions = if (feeColumnName != null) keepConditions(amountMapping.feeConditions) else emptyList()
        }
        else -> {
            amountColumnName = null
            flipAccountsOnPositive = true
            feeColumnName = null
            feeConditions = emptyList()
        }
    }

    val currencyMapping = strategy.fieldMappings[TransferField.CURRENCY]
    val currencyMode: CurrencyMode
    val selectedCurrencyId: CurrencyId?
    val currencyColumnName: String?
    when (currencyMapping) {
        is HardCodedCurrencyMapping -> {
            currencyMode = CurrencyMode.HARDCODED
            selectedCurrencyId = currencyMapping.currencyId
            currencyColumnName = null
        }
        is CurrencyLookupMapping -> {
            currencyMode = CurrencyMode.FROM_COLUMN
            selectedCurrencyId = null
            currencyColumnName = columnIfExists(currencyMapping.columnName)
        }
        else -> {
            currencyMode = CurrencyMode.HARDCODED
            selectedCurrencyId = null
            currencyColumnName = null
        }
    }

    val timezoneMapping = strategy.fieldMappings[TransferField.TIMEZONE]
    val timezoneMode: TimezoneMode
    val selectedTimezone: String
    val timezoneColumnName: String?
    when (timezoneMapping) {
        is HardCodedTimezoneMapping -> {
            timezoneMode = TimezoneMode.HARDCODED
            selectedTimezone = timezoneMapping.timezoneId
            timezoneColumnName = null
        }
        is TimezoneLookupMapping -> {
            timezoneMode = TimezoneMode.FROM_COLUMN
            selectedTimezone = TimeZone.currentSystemDefault().id
            timezoneColumnName = columnIfExists(timezoneMapping.columnName)
        }
        else -> {
            timezoneMode = TimezoneMode.HARDCODED
            selectedTimezone = TimeZone.currentSystemDefault().id
            timezoneColumnName = null
        }
    }

    // Filter attribute mappings to only include columns that exist
    val attributeMappings = strategy.attributeMappings.filter { it.columnName in availableColumnNames }

    // Keep only preprocessing rules whose referenced columns all still exist.
    val rowPreprocessingRules =
        strategy.rowPreprocessingRules.mapNotNull { rule ->
            val swaps =
                rule.columnSwaps.filter {
                    it.firstColumn in availableColumnNames && it.secondColumn in availableColumnNames
                }
            val conditions = keepConditions(rule.conditions)
            if (conditions.size != rule.conditions.size || swaps.size != rule.columnSwaps.size) {
                null
            } else {
                rule.copy(conditions = conditions, columnSwaps = swaps)
            }
        }

    return StrategyFormState(
        name = strategy.name,
        identificationColumns = strategy.identificationColumns.filter { it in availableColumnNames }.toSet(),
        dateColumnName = dateColumnName,
        dateFormat = dateFormat,
        timeColumnName = timeColumnName,
        timeFormat = timeFormat,
        descriptionColumnName = descriptionColumnName,
        descriptionFallbackColumns = descriptionFallbackColumns,
        amountColumnName = amountColumnName,
        flipAccountsOnPositive = flipAccountsOnPositive,
        feeColumnName = feeColumnName,
        feeConditions = feeConditions,
        dateTimeFormat = dateTimeFormat,
        sourceAccountMode = sourceAccountMode,
        selectedAccountId = selectedAccountId,
        sourceTemplateColumnName = sourceTemplateColumnName,
        sourceTemplatePrefix = sourceTemplatePrefix,
        sourceTemplateSuffix = sourceTemplateSuffix,
        targetAccountColumnName = targetAccountColumnName,
        targetAccountFallbackColumns = targetAccountFallbackColumns,
        targetAccountMode = targetAccountMode,
        regexRules = regexRules,
        targetTemplateColumnName = targetTemplateColumnName,
        targetTemplatePrefix = targetTemplatePrefix,
        targetTemplateSuffix = targetTemplateSuffix,
        targetConditions = targetConditions,
        targetWhenTrue = targetWhenTrue,
        targetWhenFalse = targetWhenFalse,
        currencyMode = currencyMode,
        selectedCurrencyId = selectedCurrencyId,
        currencyColumnName = currencyColumnName,
        timezoneMode = timezoneMode,
        selectedTimezone = selectedTimezone,
        timezoneColumnName = timezoneColumnName,
        attributeMappings = attributeMappings,
        rowPreprocessingRules = rowPreprocessingRules,
        companionTransactionRules = strategy.companionTransactionRules,
        contentMatchRules = strategy.contentMatchRules,
        fileNamePattern = strategy.fileNamePattern,
        crossSourceReconcileWindowSeconds = strategy.crossSourceReconcileWindowSeconds,
        fundingCardColumn = strategy.fundingCardColumn,
    )
}

/**
 * Builds a [CsvImportStrategy] from the editor's form state. Shared by the save handler and
 * tests so the round-trip (extract → edit → save) is exercised by a single code path.
 *
 * Required columns (date, description, amount, and the target column/template depending on mode)
 * are asserted non-null; callers gate this behind form validation.
 */
internal fun buildStrategyFromFormState(
    state: StrategyFormState,
    id: CsvImportStrategyId,
    createdAt: Instant,
    updatedAt: Instant,
): CsvImportStrategy {
    val fieldMappings =
        buildMap {
            when (state.sourceAccountMode) {
                SourceAccountMode.FIXED_ACCOUNT ->
                    state.selectedAccountId?.let { accountId ->
                        put(
                            TransferField.SOURCE_ACCOUNT,
                            HardCodedAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                accountId = accountId,
                            ),
                        )
                    }
                SourceAccountMode.TEMPLATE ->
                    state.sourceTemplateColumnName?.let { column ->
                        put(
                            TransferField.SOURCE_ACCOUNT,
                            TemplateAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                columnName = column,
                                prefix = state.sourceTemplatePrefix,
                                suffix = state.sourceTemplateSuffix,
                            ),
                        )
                    }
            }
            put(
                TransferField.TARGET_ACCOUNT,
                when (state.targetAccountMode) {
                    TargetAccountMode.DIRECT_LOOKUP ->
                        AccountLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetAccountColumnName!!,
                            fallbackColumns = state.targetAccountFallbackColumns,
                        )
                    TargetAccountMode.REGEX_MATCH ->
                        RegexAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetAccountColumnName!!,
                            rules = state.regexRules,
                            fallbackColumns = state.targetAccountFallbackColumns,
                        )
                    TargetAccountMode.TEMPLATE ->
                        TemplateAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetTemplateColumnName!!,
                            prefix = state.targetTemplatePrefix,
                            suffix = state.targetTemplateSuffix,
                        )
                    TargetAccountMode.CONDITIONAL ->
                        ConditionalAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            conditions = state.targetConditions,
                            whenTrue = state.targetWhenTrue,
                            whenFalse = state.targetWhenFalse,
                        )
                },
            )
            put(
                TransferField.TIMESTAMP,
                DateTimeParsingMapping(
                    id = FieldMappingId(Uuid.random()),
                    fieldType = TransferField.TIMESTAMP,
                    dateColumnName = state.dateColumnName!!,
                    dateFormat = state.dateFormat,
                    timeColumnName = state.timeColumnName,
                    timeFormat = state.timeColumnName?.let { state.timeFormat },
                    dateTimeFormat = state.dateTimeFormat.takeIf { it.isNotBlank() },
                ),
            )
            put(
                TransferField.DESCRIPTION,
                DirectColumnMapping(
                    id = FieldMappingId(Uuid.random()),
                    fieldType = TransferField.DESCRIPTION,
                    columnName = state.descriptionColumnName!!,
                    fallbackColumns = state.descriptionFallbackColumns,
                ),
            )
            put(
                TransferField.AMOUNT,
                AmountParsingMapping(
                    id = FieldMappingId(Uuid.random()),
                    fieldType = TransferField.AMOUNT,
                    mode = AmountMode.SINGLE_COLUMN,
                    amountColumnName = state.amountColumnName!!,
                    flipAccountsOnPositive = state.flipAccountsOnPositive,
                    feeColumnName = state.feeColumnName,
                    feeConditions = if (state.feeColumnName != null) state.feeConditions else emptyList(),
                ),
            )
            put(
                TransferField.CURRENCY,
                when (state.currencyMode) {
                    CurrencyMode.HARDCODED ->
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = state.selectedCurrencyId!!,
                        )
                    CurrencyMode.FROM_COLUMN ->
                        CurrencyLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            columnName = state.currencyColumnName!!,
                        )
                },
            )
            put(
                TransferField.TIMEZONE,
                when (state.timezoneMode) {
                    TimezoneMode.HARDCODED ->
                        HardCodedTimezoneMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMEZONE,
                            timezoneId = state.selectedTimezone,
                        )
                    TimezoneMode.FROM_COLUMN ->
                        TimezoneLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMEZONE,
                            columnName = state.timezoneColumnName!!,
                        )
                },
            )
        }
    return CsvImportStrategy(
        id = id,
        name = state.name,
        identificationColumns = state.identificationColumns,
        fieldMappings = fieldMappings,
        attributeMappings = state.attributeMappings,
        rowPreprocessingRules = state.rowPreprocessingRules,
        companionTransactionRules = state.companionTransactionRules,
        contentMatchRules = state.contentMatchRules,
        fileNamePattern = state.fileNamePattern?.takeIf { it.isNotBlank() },
        crossSourceReconcileWindowSeconds = state.crossSourceReconcileWindowSeconds,
        fundingCardColumn = state.fundingCardColumn?.takeIf { it.isNotBlank() },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
