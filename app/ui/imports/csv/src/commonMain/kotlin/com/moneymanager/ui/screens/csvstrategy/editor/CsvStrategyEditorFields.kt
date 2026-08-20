package com.moneymanager.ui.screens.csvstrategy.editor

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.AttributeMatchAccountMapping
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.time.Instant

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
    ATTRIBUTE_MATCH,
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
    return when (kind) {
        LeafAccountKind.LOOKUP -> AccountLookupMapping(fieldType, columnName = column)
        LeafAccountKind.REGEX -> RegexAccountMapping(fieldType, columnName = column, rules = emptyList())
        LeafAccountKind.TEMPLATE -> TemplateAccountMapping(fieldType, columnName = column)
    }
}

internal fun emptyTargetAccountMapping(): FieldMapping = AccountLookupMapping(TransferField.TARGET_ACCOUNT, columnName = "")

internal fun attributeCandidateColumns(
    csvColumns: List<CsvColumn>,
    primaryFieldColumnNames: Set<String?>,
): List<CsvColumn> {
    val usedPrimaryFieldColumns = primaryFieldColumnNames.filterNotNull().toSet()
    return csvColumns.filter { it.originalName !in usedPrimaryFieldColumns }
}

/** Null unless this column still exists in the uploaded CSV. */
internal fun String?.takeIfPresentIn(columns: Set<String>): String? = this?.takeIf { it in columns }

/** Drops conditions referencing columns absent from the uploaded CSV. */
internal fun List<RowCondition>?.keepPresentIn(columns: Set<String>): List<RowCondition> =
    this.orEmpty().filter { it.columnName in columns && (it.otherColumnName == null || it.otherColumnName in columns) }

/**
 * Clears column references on a conditional branch's leaf mapping when those columns no longer exist,
 * so stale references don't survive loading and get persisted again on save.
 */
internal fun FieldMapping.withColumnsPresentIn(columns: Set<String>): FieldMapping =
    when (this) {
        is AccountLookupMapping ->
            copy(
                columnName = columnName.takeIfPresentIn(columns).orEmpty(),
                fallbackColumns = fallbackColumns.mapNotNull { it.takeIfPresentIn(columns) },
            )
        is RegexAccountMapping ->
            copy(
                columnName = columnName.takeIfPresentIn(columns).orEmpty(),
                fallbackColumns = fallbackColumns.mapNotNull { it.takeIfPresentIn(columns) },
            )
        is TemplateAccountMapping -> copy(columnName = columnName.takeIfPresentIn(columns).orEmpty())
        else -> this
    }

/**
 * Builds a [CsvImportStrategy] from the editor's live state. Shared by the save handler and tests so
 * the round-trip (load → edit → save) is exercised by a single code path.
 *
 * Required columns (date, description, amount, and the target column/template depending on mode)
 * are asserted non-null; callers gate this behind form validation.
 */
internal fun buildStrategyFromEditorState(
    state: CsvStrategyEditorState,
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
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetAccountColumnName!!,
                            fallbackColumns = state.targetAccountFallbackColumns,
                        )
                    TargetAccountMode.REGEX_MATCH ->
                        RegexAccountMapping(
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetAccountColumnName!!,
                            rules = state.regexRules,
                            fallbackColumns = state.targetAccountFallbackColumns,
                        )
                    TargetAccountMode.ATTRIBUTE_MATCH ->
                        AttributeMatchAccountMapping(
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetAccountColumnName!!,
                            attributeTypeName = state.targetAttributeTypeName!!,
                        )
                    TargetAccountMode.TEMPLATE ->
                        TemplateAccountMapping(
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = state.targetTemplateColumnName!!,
                            prefix = state.targetTemplatePrefix,
                            suffix = state.targetTemplateSuffix,
                        )
                    TargetAccountMode.CONDITIONAL ->
                        ConditionalAccountMapping(
                            fieldType = TransferField.TARGET_ACCOUNT,
                            conditions = state.targetConditions,
                            whenTrue = state.targetWhenTrue,
                            whenFalse = state.targetWhenFalse,
                        )
                },
            )
            // The two modes are mutually exclusive: a combined format ignores any separate time
            // column, so null it out to keep the saved mapping consistent with the chosen mode.
            val timeColumnName = state.timeColumnName.takeUnless { state.dateTimeInOneColumn }
            put(
                TransferField.TIMESTAMP,
                DateTimeParsingMapping(
                    fieldType = TransferField.TIMESTAMP,
                    dateColumnName = state.dateColumnName!!,
                    dateFormat = state.dateFormat,
                    timeColumnName = timeColumnName,
                    timeFormat = timeColumnName?.let { state.timeFormat },
                    dateTimeFormat = state.dateTimeFormat.takeIf { state.dateTimeInOneColumn && it.isNotBlank() },
                ),
            )
            put(
                TransferField.DESCRIPTION,
                DirectColumnMapping(
                    fieldType = TransferField.DESCRIPTION,
                    columnName = state.descriptionColumnName!!,
                    fallbackColumns = state.descriptionFallbackColumns,
                ),
            )
            put(
                TransferField.AMOUNT,
                AmountParsingMapping(
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
                            fieldType = TransferField.CURRENCY,
                            currencyId = state.selectedCurrencyId!!,
                        )
                    CurrencyMode.FROM_COLUMN ->
                        CurrencyLookupMapping(
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
                            fieldType = TransferField.TIMEZONE,
                            timezoneId = state.selectedTimezone,
                        )
                    TimezoneMode.FROM_COLUMN ->
                        TimezoneLookupMapping(
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
        fileNamePattern = state.fileNamePattern.takeIf { it.isNotBlank() },
        crossSourceReconcileWindowSeconds = state.crossSourceReconcileWindowSeconds,
        // A funding match is saved only once a column is chosen; the attribute type always has a value.
        fundingAttributeMatch =
            state.fundingMatchColumn
                ?.takeIf { it.isNotBlank() }
                ?.let { AttributeAccountMatch(column = it, attributeTypeName = state.fundingMatchAttributeTypeName) },
        conversionConfig = state.conversionConfig,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
