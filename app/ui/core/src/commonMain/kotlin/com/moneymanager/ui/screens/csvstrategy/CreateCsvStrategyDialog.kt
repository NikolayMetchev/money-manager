@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
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
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.transactions.AttributeTypeField
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Currency mapping mode for CSV import.
 */
enum class CurrencyMode {
    HARDCODED,
    FROM_COLUMN,
}

/**
 * Timezone mapping mode for CSV import.
 */
enum class TimezoneMode {
    HARDCODED,
    FROM_COLUMN,
}

internal fun attributeCandidateColumns(
    csvColumns: List<CsvColumn>,
    primaryFieldColumnNames: Set<String?>,
): List<CsvColumn> {
    val usedPrimaryFieldColumns = primaryFieldColumnNames.filterNotNull().toSet()
    return csvColumns.filter { it.originalName !in usedPrimaryFieldColumns }
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
private enum class LeafAccountKind {
    LOOKUP,
    REGEX,
    TEMPLATE,
}

private fun LeafAccountKind.label(): String =
    when (this) {
        LeafAccountKind.LOOKUP -> "Lookup"
        LeafAccountKind.REGEX -> "Regex"
        LeafAccountKind.TEMPLATE -> "Template"
    }

private fun RowConditionOperator.label(): String =
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
private fun RowCondition.isComplete(): Boolean =
    columnName.isNotBlank() &&
        when (operator) {
            RowConditionOperator.EQUALS_VALUE -> !value.isNullOrBlank()
            RowConditionOperator.EQUALS_COLUMN, RowConditionOperator.NOT_EQUALS_COLUMN -> !otherColumnName.isNullOrBlank()
            RowConditionOperator.IS_BLANK, RowConditionOperator.IS_NOT_BLANK -> true
        }

/**
 * Whether a conditional-branch account mapping is fully specified.
 */
private fun FieldMapping.isLeafAccountValid(): Boolean =
    when (this) {
        is AccountLookupMapping -> columnName.isNotBlank()
        is RegexAccountMapping -> columnName.isNotBlank() && rules.isNotEmpty() && rules.all { it.accountName.isNotBlank() }
        is TemplateAccountMapping -> columnName.isNotBlank()
        is HardCodedAccountMapping -> true
        else -> false
    }

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun defaultLeafAccountMapping(
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

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun emptyTargetAccountMapping(): FieldMapping =
    AccountLookupMapping(FieldMappingId(Uuid.random()), TransferField.TARGET_ACCOUNT, columnName = "")

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
)

/**
 * Extracts form state from an existing strategy.
 * Returns null for columns that don't exist in the current CSV.
 */
internal fun extractFormStateFromStrategy(
    strategy: CsvImportStrategy,
    availableColumnNames: Set<String>,
): StrategyFormState {
    // Helper to check if column exists
    fun columnIfExists(name: String?): String? = name?.takeIf { it in availableColumnNames }

    // Drop conditions referencing columns absent from the uploaded CSV.
    fun keepConditions(conditions: List<RowCondition>): List<RowCondition> =
        conditions.filter { c ->
            c.columnName in availableColumnNames &&
                (c.otherColumnName == null || c.otherColumnName in availableColumnNames)
        }

    // Extract source account mapping
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

    // Extract target account column
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
            targetWhenTrue = targetAccountMapping.whenTrue
            targetWhenFalse = targetAccountMapping.whenFalse
        }
        else -> {
            targetAccountColumnName = null
            targetAccountFallbackColumns = emptyList()
            targetAccountMode = TargetAccountMode.DIRECT_LOOKUP
            regexRules = emptyList()
        }
    }

    // Extract timestamp mapping
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

    // Extract description column
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

    // Extract amount mapping
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

    // Extract currency mapping
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

    // Extract timezone mapping
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
    )
}

/**
 * Builds a [CsvImportStrategy] from the dialog's form state. Shared by the save handler and
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
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * Dialog for creating or editing a CSV import strategy.
 *
 * @param existingStrategy If provided, the dialog operates in edit mode and pre-populates
 *                         the form with the strategy's values. Otherwise, creates a new strategy.
 */
@Composable
fun CreateCsvStrategyDialog(
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySource: EntitySource,
    csvColumns: List<CsvColumn>,
    rows: List<CsvRow>,
    onDismiss: () -> Unit,
    existingStrategy: CsvImportStrategy? = null,
) {
    val isEditMode = existingStrategy != null
    val availableColumnNames = csvColumns.map { it.originalName }.toSet()

    // Extract initial state from existing strategy if in edit mode
    val initialState =
        existingStrategy?.let {
            extractFormStateFromStrategy(it, availableColumnNames)
        }
    var name by remember { mutableStateOf(initialState?.name.orEmpty()) }
    var identificationColumns by remember {
        mutableStateOf(initialState?.identificationColumns ?: csvColumns.map { it.originalName }.toSet())
    }
    var dateColumnName by remember { mutableStateOf(initialState?.dateColumnName) }
    var dateFormat by remember { mutableStateOf(initialState?.dateFormat ?: "dd/MM/yyyy") }
    var timeColumnName by remember { mutableStateOf(initialState?.timeColumnName) }
    var timeFormat by remember { mutableStateOf(initialState?.timeFormat ?: "HH:mm:ss") }
    var descriptionColumnName by remember { mutableStateOf(initialState?.descriptionColumnName) }
    var descriptionFallbackColumns by remember {
        mutableStateOf(initialState?.descriptionFallbackColumns.orEmpty())
    }
    var amountColumnName by remember { mutableStateOf(initialState?.amountColumnName) }
    var feeColumnName by remember { mutableStateOf(initialState?.feeColumnName) }
    var feeConditions by remember { mutableStateOf(initialState?.feeConditions.orEmpty()) }
    var dateTimeFormat by remember { mutableStateOf(initialState?.dateTimeFormat.orEmpty()) }
    var sourceAccountMode by remember {
        mutableStateOf(initialState?.sourceAccountMode ?: SourceAccountMode.FIXED_ACCOUNT)
    }
    var selectedAccountId by remember { mutableStateOf(initialState?.selectedAccountId) }
    var sourceTemplateColumnName by remember { mutableStateOf(initialState?.sourceTemplateColumnName) }
    var sourceTemplatePrefix by remember { mutableStateOf(initialState?.sourceTemplatePrefix.orEmpty()) }
    var sourceTemplateSuffix by remember { mutableStateOf(initialState?.sourceTemplateSuffix.orEmpty()) }
    var selectedCurrencyId by remember { mutableStateOf(initialState?.selectedCurrencyId) }
    var currencyMode by remember { mutableStateOf(initialState?.currencyMode ?: CurrencyMode.HARDCODED) }
    var currencyColumnName by remember { mutableStateOf(initialState?.currencyColumnName) }
    var timezoneMode by remember { mutableStateOf(initialState?.timezoneMode ?: TimezoneMode.HARDCODED) }
    var selectedTimezone by remember {
        mutableStateOf(initialState?.selectedTimezone ?: TimeZone.currentSystemDefault().id)
    }
    var timezoneColumnName by remember { mutableStateOf(initialState?.timezoneColumnName) }
    var targetAccountColumnName by remember { mutableStateOf(initialState?.targetAccountColumnName) }
    var targetAccountFallbackColumns by remember {
        mutableStateOf(initialState?.targetAccountFallbackColumns.orEmpty())
    }
    var targetAccountMode by remember {
        mutableStateOf(initialState?.targetAccountMode ?: TargetAccountMode.DIRECT_LOOKUP)
    }
    var regexRules by remember { mutableStateOf(initialState?.regexRules.orEmpty()) }
    var targetTemplateColumnName by remember { mutableStateOf(initialState?.targetTemplateColumnName) }
    var targetTemplatePrefix by remember { mutableStateOf(initialState?.targetTemplatePrefix.orEmpty()) }
    var targetTemplateSuffix by remember { mutableStateOf(initialState?.targetTemplateSuffix.orEmpty()) }
    var targetConditions by remember { mutableStateOf(initialState?.targetConditions.orEmpty()) }
    var targetWhenTrue by remember {
        mutableStateOf(initialState?.targetWhenTrue ?: emptyTargetAccountMapping())
    }
    var targetWhenFalse by remember {
        mutableStateOf(initialState?.targetWhenFalse ?: emptyTargetAccountMapping())
    }
    var flipAccountsOnPositive by remember { mutableStateOf(initialState?.flipAccountsOnPositive ?: true) }
    // List of attribute column mappings with unique identifier flags
    var attributeMappings by remember {
        mutableStateOf(initialState?.attributeMappings.orEmpty())
    }
    var rowPreprocessingRules by remember { mutableStateOf(initialState?.rowPreprocessingRules.orEmpty()) }
    var companionTransactionRules by remember {
        mutableStateOf(initialState?.companionTransactionRules.orEmpty())
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberSchemaAwareCoroutineScope()

    // Fetch existing attribute types
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    LaunchedEffect(Unit) {
        attributeTypeRepository.getAll().collect { types ->
            existingAttributeTypes = types
        }
    }

    // Fetch account mappings for this strategy (edit mode only)
    var accountMappings by remember { mutableStateOf<List<CsvAccountMapping>>(emptyList()) }
    var editingAccountMapping by remember { mutableStateOf<CsvAccountMapping?>(null) }
    var showAddAccountMappingDialog by remember { mutableStateOf(false) }

    // Fetch all accounts for mapping selection
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(emptyList())

    LaunchedEffect(existingStrategy?.id) {
        existingStrategy?.let { strategy ->
            csvAccountMappingRepository.getMappingsForStrategy(strategy.id).collect { mappings ->
                accountMappings = mappings
            }
        }
    }

    // Use first row for sample values (backward compatible)
    val firstRow = rows.firstOrNull()

    // Build sample values map for value-based detection
    val sampleValues: Map<Int, String>? =
        firstRow?.let { row ->
            csvColumns.associate { col -> col.columnIndex to row.values.getOrNull(col.columnIndex).orEmpty() }
        }

    // Compute form validity - all required fields must be populated
    val targetAccountValid =
        when (targetAccountMode) {
            TargetAccountMode.DIRECT_LOOKUP -> targetAccountColumnName != null
            TargetAccountMode.REGEX_MATCH ->
                targetAccountColumnName != null &&
                    regexRules.isNotEmpty() &&
                    regexRules.all { it.accountName.isNotBlank() }
            TargetAccountMode.TEMPLATE -> targetTemplateColumnName != null
            TargetAccountMode.CONDITIONAL ->
                targetConditions.isNotEmpty() &&
                    targetConditions.all { it.isComplete() } &&
                    targetWhenTrue.isLeafAccountValid() &&
                    targetWhenFalse.isLeafAccountValid()
        }

    val feeValid = feeColumnName == null || feeConditions.all { it.isComplete() }
    val rowPreprocessingValid =
        rowPreprocessingRules.all { rule ->
            rule.conditions.all { it.isComplete() } &&
                rule.columnSwaps.all { it.firstColumn.isNotBlank() && it.secondColumn.isNotBlank() }
        }
    val companionRulesValid =
        companionTransactionRules.all {
            it.name.isNotBlank() && it.matchAttributeName.isNotBlank() && it.matchValuePattern.isNotBlank()
        }

    val isFormValid =
        name.isNotBlank() &&
            identificationColumns.isNotEmpty() &&
            targetAccountValid &&
            dateColumnName != null &&
            descriptionColumnName != null &&
            amountColumnName != null &&
            feeValid &&
            rowPreprocessingValid &&
            companionRulesValid &&
            when (currencyMode) {
                CurrencyMode.HARDCODED -> selectedCurrencyId != null
                CurrencyMode.FROM_COLUMN -> currencyColumnName != null
            } &&
            when (timezoneMode) {
                TimezoneMode.HARDCODED -> true // Always valid, defaults to system timezone
                TimezoneMode.FROM_COLUMN -> timezoneColumnName != null
            }

    // Auto-detect columns on first load (only in create mode, not edit mode)
    LaunchedEffect(csvColumns, firstRow) {
        if (!isEditMode) {
            if (dateColumnName == null) {
                dateColumnName = ColumnDetector.suggestDateColumn(csvColumns, sampleValues)
            }
            if (timeColumnName == null) {
                timeColumnName = ColumnDetector.suggestTimeColumn(csvColumns, sampleValues)
            }
            if (descriptionColumnName == null) {
                descriptionColumnName = ColumnDetector.suggestDescriptionColumn(csvColumns)
            }
            if (amountColumnName == null) {
                amountColumnName = ColumnDetector.suggestAmountColumn(csvColumns, sampleValues)
            }
            if (targetAccountColumnName == null) {
                targetAccountColumnName = ColumnDetector.suggestPayeeColumn(csvColumns)
            }
            if (currencyColumnName == null) {
                currencyColumnName = ColumnDetector.suggestCurrencyColumn(csvColumns, sampleValues)
                if (currencyColumnName != null) {
                    currencyMode = CurrencyMode.FROM_COLUMN
                }
            }
        }
    }

    // Track initial column names to avoid overwriting saved fallback columns on edit mode load.
    // Auto-detection should only trigger when the user changes the primary column, not on initial load.
    val initialTargetAccountColumnName = remember { initialState?.targetAccountColumnName }
    val initialDescriptionColumnName = remember { initialState?.descriptionColumnName }

    // Auto-detect fallback columns when target column is selected
    LaunchedEffect(targetAccountColumnName, rows) {
        val primaryColumn = targetAccountColumnName
        if (primaryColumn != null && primaryColumn != initialTargetAccountColumnName && rows.isNotEmpty()) {
            targetAccountFallbackColumns =
                ColumnDetector.suggestFallbackColumns(
                    primaryColumn = primaryColumn,
                    columns = csvColumns,
                    rows = rows,
                )
        }
    }

    // Auto-detect fallback columns when description column is selected
    LaunchedEffect(descriptionColumnName, rows) {
        val primaryColumn = descriptionColumnName
        if (primaryColumn != null && primaryColumn != initialDescriptionColumnName && rows.isNotEmpty()) {
            descriptionFallbackColumns =
                ColumnDetector.suggestFallbackColumns(
                    primaryColumn = primaryColumn,
                    columns = csvColumns,
                    rows = rows,
                )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isEditMode) "Edit Import Strategy" else "Create Import Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Strategy Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = name.isBlank(),
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Identification Columns", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Select columns that uniquely identify this CSV format",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                IdentificationColumnsSelector(
                    columns = csvColumns,
                    selectedColumns = identificationColumns,
                    onSelectionChanged = { identificationColumns = it },
                    enabled = !isSaving,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Source Account (Optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Can also be chosen each time you apply this strategy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = sourceAccountMode == SourceAccountMode.FIXED_ACCOUNT,
                        onClick = { sourceAccountMode = SourceAccountMode.FIXED_ACCOUNT },
                        enabled = !isSaving,
                    )
                    Text("Fixed Account", modifier = Modifier.padding(end = 16.dp))
                    RadioButton(
                        selected = sourceAccountMode == SourceAccountMode.TEMPLATE,
                        onClick = { sourceAccountMode = SourceAccountMode.TEMPLATE },
                        enabled = !isSaving,
                    )
                    Text("From Column (Template)")
                }
                when (sourceAccountMode) {
                    SourceAccountMode.FIXED_ACCOUNT ->
                        AccountPicker(
                            selectedAccountId = selectedAccountId,
                            onAccountSelected = { selectedAccountId = it },
                            label = "Select Account",
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            personAccountOwnershipRepository = personAccountOwnershipRepository,
                            entitySource = entitySource,
                            enabled = !isSaving,
                        )
                    SourceAccountMode.TEMPLATE ->
                        TemplateAccountMappingEditor(
                            columnName = sourceTemplateColumnName,
                            onColumnChanged = { sourceTemplateColumnName = it },
                            prefix = sourceTemplatePrefix,
                            onPrefixChanged = { sourceTemplatePrefix = it },
                            suffix = sourceTemplateSuffix,
                            onSuffixChanged = { sourceTemplateSuffix = it },
                            columns = csvColumns,
                            firstRow = firstRow,
                            enabled = !isSaving,
                        )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Target Account", style = MaterialTheme.typography.titleSmall)
                Text(
                    "How the target account is resolved for each row",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TargetAccountModeSelector(
                    selected = targetAccountMode,
                    onSelected = { targetAccountMode = it },
                    enabled = !isSaving,
                )

                when (targetAccountMode) {
                    TargetAccountMode.DIRECT_LOOKUP, TargetAccountMode.REGEX_MATCH -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        ColumnDropdown(
                            columns = csvColumns,
                            selectedColumn = targetAccountColumnName,
                            onColumnSelected = { targetAccountColumnName = it },
                            label = "Column for payee/counterparty name",
                            sampleValue = getSampleValue(csvColumns, firstRow, targetAccountColumnName),
                            enabled = !isSaving,
                            isError = targetAccountColumnName == null,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Fallback column (when primary is empty)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Find a row where the primary column is blank to show a relevant sample
                        val fallbackSampleRow = findRowWithBlankColumn(csvColumns, rows, targetAccountColumnName)
                        OptionalColumnDropdown(
                            columns = csvColumns,
                            selectedColumn = targetAccountFallbackColumns.firstOrNull(),
                            onColumnSelected = { selected ->
                                targetAccountFallbackColumns = if (selected != null) listOf(selected) else emptyList()
                            },
                            label = "Fallback column for account name",
                            sampleValue = getSampleValue(csvColumns, fallbackSampleRow, targetAccountFallbackColumns.firstOrNull()),
                            enabled = !isSaving,
                        )

                        if (targetAccountMode == TargetAccountMode.REGEX_MATCH) {
                            Spacer(modifier = Modifier.height(8.dp))
                            RegexRulesEditor(
                                rules = regexRules,
                                onRulesChanged = { regexRules = it },
                                columnName = targetAccountColumnName,
                                columns = csvColumns,
                                rows = rows,
                                enabled = !isSaving,
                            )
                        }
                    }
                    TargetAccountMode.TEMPLATE -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        TemplateAccountMappingEditor(
                            columnName = targetTemplateColumnName,
                            onColumnChanged = { targetTemplateColumnName = it },
                            prefix = targetTemplatePrefix,
                            onPrefixChanged = { targetTemplatePrefix = it },
                            suffix = targetTemplateSuffix,
                            onSuffixChanged = { targetTemplateSuffix = it },
                            columns = csvColumns,
                            firstRow = firstRow,
                            enabled = !isSaving,
                        )
                    }
                    TargetAccountMode.CONDITIONAL -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        ConditionalAccountMappingEditor(
                            conditions = targetConditions,
                            onConditionsChanged = { targetConditions = it },
                            whenTrue = targetWhenTrue,
                            onWhenTrueChanged = { targetWhenTrue = it },
                            whenFalse = targetWhenFalse,
                            onWhenFalseChanged = { targetWhenFalse = it },
                            columns = csvColumns,
                            rows = rows,
                            firstRow = firstRow,
                            enabled = !isSaving,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Date Column", style = MaterialTheme.typography.titleSmall)
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = dateColumnName,
                    onColumnSelected = { dateColumnName = it },
                    label = "Column containing transaction date",
                    sampleValue = getSampleValue(csvColumns, firstRow, dateColumnName),
                    enabled = !isSaving,
                    isError = dateColumnName == null,
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = dateFormat,
                    onValueChange = { dateFormat = it },
                    label = { Text("Date Format (e.g., dd/MM/yyyy)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    supportingText = {
                        getSampleValue(csvColumns, firstRow, dateColumnName)?.let {
                            Text("Sample: $it")
                        }
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Time Column (Optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Select if time is in a separate column",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                OptionalColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = timeColumnName,
                    onColumnSelected = { timeColumnName = it },
                    label = "Column containing transaction time",
                    sampleValue = getSampleValue(csvColumns, firstRow, timeColumnName),
                    enabled = !isSaving,
                )
                if (timeColumnName != null && dateTimeFormat.isBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = timeFormat,
                        onValueChange = { timeFormat = it },
                        label = { Text("Time Format (e.g., HH:mm:ss)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSaving,
                        supportingText = {
                            getSampleValue(csvColumns, firstRow, timeColumnName)?.let {
                                Text("Sample: $it")
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = dateTimeFormat,
                    onValueChange = { dateTimeFormat = it },
                    label = { Text("Combined date+time format (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    supportingText = {
                        Text(
                            "When set, the date column holds both date and time " +
                                "(e.g., yyyy-MM-dd HH:mm:ss) and the separate time column is ignored.",
                        )
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Description Column", style = MaterialTheme.typography.titleSmall)
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = descriptionColumnName,
                    onColumnSelected = { descriptionColumnName = it },
                    label = "Column containing transaction description",
                    sampleValue = getSampleValue(csvColumns, firstRow, descriptionColumnName),
                    enabled = !isSaving,
                    isError = descriptionColumnName == null,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Fallback column (when primary is empty)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Find a row where the primary column is blank to show a relevant sample
                val descriptionFallbackSampleRow =
                    findRowWithBlankColumn(csvColumns, rows, descriptionColumnName)
                OptionalColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = descriptionFallbackColumns.firstOrNull(),
                    onColumnSelected = { selected ->
                        descriptionFallbackColumns = if (selected != null) listOf(selected) else emptyList()
                    },
                    label = "Fallback column for description",
                    sampleValue = getSampleValue(csvColumns, descriptionFallbackSampleRow, descriptionFallbackColumns.firstOrNull()),
                    enabled = !isSaving,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Amount Column", style = MaterialTheme.typography.titleSmall)
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = amountColumnName,
                    onColumnSelected = { amountColumnName = it },
                    label = "Column containing transaction amount",
                    sampleValue = getSampleValue(csvColumns, firstRow, amountColumnName),
                    enabled = !isSaving,
                    isError = amountColumnName == null,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = flipAccountsOnPositive,
                        onCheckedChange = { flipAccountsOnPositive = it },
                        enabled = !isSaving,
                    )
                    Text(
                        "Swap accounts when amount is positive",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Fee column (optional)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Added to the amount's magnitude when the conditions below hold",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionalColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = feeColumnName,
                    onColumnSelected = { feeColumnName = it },
                    label = "Column containing fee amount",
                    sampleValue = getSampleValue(csvColumns, firstRow, feeColumnName),
                    enabled = !isSaving,
                )
                if (feeColumnName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    RowConditionsEditor(
                        conditions = feeConditions,
                        onConditionsChanged = { feeConditions = it },
                        columns = csvColumns,
                        enabled = !isSaving,
                        title = "Apply fee when (all conditions match; none = always)",
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Currency", style = MaterialTheme.typography.titleSmall)

                // Mode toggle using Row with RadioButtons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = currencyMode == CurrencyMode.HARDCODED,
                        onClick = { currencyMode = CurrencyMode.HARDCODED },
                        enabled = !isSaving,
                    )
                    Text("Fixed Currency", modifier = Modifier.padding(end = 16.dp))
                    RadioButton(
                        selected = currencyMode == CurrencyMode.FROM_COLUMN,
                        onClick = { currencyMode = CurrencyMode.FROM_COLUMN },
                        enabled = !isSaving,
                    )
                    Text("From CSV Column")
                }

                // Show appropriate input based on mode
                when (currencyMode) {
                    CurrencyMode.HARDCODED -> {
                        CurrencyPicker(
                            selectedCurrencyId = selectedCurrencyId,
                            onCurrencySelected = { selectedCurrencyId = it },
                            label = "Select Currency",
                            currencyRepository = currencyRepository,
                            enabled = !isSaving,
                            isError = selectedCurrencyId == null,
                        )
                    }
                    CurrencyMode.FROM_COLUMN -> {
                        ColumnDropdown(
                            columns = csvColumns,
                            selectedColumn = currencyColumnName,
                            onColumnSelected = { currencyColumnName = it },
                            label = "Column containing currency code",
                            sampleValue = getSampleValue(csvColumns, firstRow, currencyColumnName),
                            enabled = !isSaving,
                            isError = currencyColumnName == null,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Timezone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Timezone for interpreting date/time values",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Mode toggle using Row with RadioButtons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = timezoneMode == TimezoneMode.HARDCODED,
                        onClick = { timezoneMode = TimezoneMode.HARDCODED },
                        enabled = !isSaving,
                    )
                    Text("Fixed Timezone", modifier = Modifier.padding(end = 16.dp))
                    RadioButton(
                        selected = timezoneMode == TimezoneMode.FROM_COLUMN,
                        onClick = { timezoneMode = TimezoneMode.FROM_COLUMN },
                        enabled = !isSaving,
                    )
                    Text("From CSV Column")
                }

                // Show appropriate input based on mode
                when (timezoneMode) {
                    TimezoneMode.HARDCODED -> {
                        TimezonePicker(
                            selectedTimezone = selectedTimezone,
                            onTimezoneSelected = { selectedTimezone = it },
                            enabled = !isSaving,
                        )
                    }
                    TimezoneMode.FROM_COLUMN -> {
                        ColumnDropdown(
                            columns = csvColumns,
                            selectedColumn = timezoneColumnName,
                            onColumnSelected = { timezoneColumnName = it },
                            label = "Column containing timezone ID",
                            sampleValue = getSampleValue(csvColumns, firstRow, timezoneColumnName),
                            enabled = !isSaving,
                            isError = timezoneColumnName == null,
                        )
                    }
                }

                // Attributes section
                Spacer(modifier = Modifier.height(16.dp))
                Text("Attributes (Optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Select columns to store as attributes (metadata)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))

                val attributeColumns =
                    attributeCandidateColumns(
                        csvColumns = csvColumns,
                        primaryFieldColumnNames =
                            setOf(
                                dateColumnName,
                                timeColumnName,
                                descriptionColumnName,
                                amountColumnName,
                                targetAccountColumnName,
                                currencyColumnName,
                                timezoneColumnName,
                            ),
                    )

                if (attributeColumns.isEmpty()) {
                    Text(
                        "No columns available for attributes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AttributeMappingsEditor(
                        columns = attributeColumns,
                        mappings = attributeMappings,
                        onMappingsChanged = { attributeMappings = it },
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = !isSaving,
                        firstRow = firstRow,
                    )
                }

                // Row preprocessing rules section
                Spacer(modifier = Modifier.height(16.dp))
                Text("Row Preprocessing Rules (Optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Swap column values and/or flip source/target accounts when conditions match",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                RowPreprocessingRulesEditor(
                    rules = rowPreprocessingRules,
                    onRulesChanged = { rowPreprocessingRules = it },
                    columns = csvColumns,
                    enabled = !isSaving,
                )

                // Companion transaction rules section
                Spacer(modifier = Modifier.height(16.dp))
                Text("Companion Transaction Rules (Optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Flag imported transfers that imply a manually entered companion transaction",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                CompanionTransactionRulesEditor(
                    rules = companionTransactionRules,
                    onRulesChanged = { companionTransactionRules = it },
                    enabled = !isSaving,
                )

                // Account Mappings section (edit mode only)
                if (isEditMode) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Account Mappings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "CSV values mapped to existing accounts (auto-created during import)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AccountMappingsSection(
                        mappings = accountMappings,
                        accounts = accounts,
                        enabled = !isSaving,
                        onEditMapping = { mapping -> editingAccountMapping = mapping },
                        onDeleteMapping = { mapping ->
                            scope.launch {
                                csvAccountMappingRepository.deleteMapping(mapping.id)
                            }
                        },
                        onAddMapping = { showAddAccountMappingDialog = true },
                    )
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Validation already done via isFormValid, but double-check
                    if (isFormValid) {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val now = Clock.System.now()
                                val formState =
                                    StrategyFormState(
                                        name = name,
                                        identificationColumns = identificationColumns,
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
                                        companionTransactionRules = companionTransactionRules,
                                    )
                                val strategy =
                                    buildStrategyFromFormState(
                                        state = formState,
                                        id = existingStrategy?.id ?: CsvImportStrategyId(Uuid.random()),
                                        createdAt = existingStrategy?.createdAt ?: now,
                                        updatedAt = now,
                                    )
                                if (isEditMode) {
                                    csvImportStrategyRepository.updateStrategy(strategy)
                                } else {
                                    csvImportStrategyRepository.createStrategy(strategy)
                                }
                                onDismiss()
                            } catch (expected: Exception) {
                                val action = if (isEditMode) "save" else "create"
                                errorMessage = "Failed to $action strategy: ${expected.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = isFormValid && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (isEditMode) "Save" else "Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )

    // Dialog for editing an existing account mapping
    editingAccountMapping?.let { mapping ->
        AccountMappingEditorDialog(
            existingMapping = mapping,
            strategyId = existingStrategy!!.id,
            accounts = accounts,
            csvAccountMappingRepository = csvAccountMappingRepository,
            onDismiss = { editingAccountMapping = null },
        )
    }

    // Dialog for adding a new account mapping
    if (showAddAccountMappingDialog && existingStrategy != null) {
        AccountMappingEditorDialog(
            existingMapping = null,
            strategyId = existingStrategy.id,
            accounts = accounts,
            csvAccountMappingRepository = csvAccountMappingRepository,
            onDismiss = { showAddAccountMappingDialog = false },
        )
    }
}

/**
 * Radio-button row selecting how the target account is resolved.
 */
@Composable
private fun TargetAccountModeSelector(
    selected: TargetAccountMode,
    onSelected: (TargetAccountMode) -> Unit,
    enabled: Boolean,
) {
    val labels =
        listOf(
            TargetAccountMode.DIRECT_LOOKUP to "Lookup",
            TargetAccountMode.REGEX_MATCH to "Regex",
            TargetAccountMode.TEMPLATE to "Template",
            TargetAccountMode.CONDITIONAL to "Conditional",
        )
    Column {
        labels.chunked(2).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.forEach { (mode, label) ->
                    RadioButton(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                        enabled = enabled,
                    )
                    Text(label, modifier = Modifier.padding(end = 16.dp))
                }
            }
        }
    }
}

/**
 * Editor for an account mapping that templates a column value into an account name
 * (`prefix + columnValue + suffix`).
 */
@Composable
private fun TemplateAccountMappingEditor(
    columnName: String?,
    onColumnChanged: (String) -> Unit,
    prefix: String,
    onPrefixChanged: (String) -> Unit,
    suffix: String,
    onSuffixChanged: (String) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ColumnDropdown(
            columns = columns,
            selectedColumn = columnName,
            onColumnSelected = onColumnChanged,
            label = "Column for account name",
            sampleValue = getSampleValue(columns, firstRow, columnName),
            enabled = enabled,
            isError = columnName == null,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = prefix,
            onValueChange = onPrefixChanged,
            label = { Text("Prefix (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = suffix,
            onValueChange = onSuffixChanged,
            label = { Text("Suffix (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        getSampleValue(columns, firstRow, columnName)?.let { sample ->
            Text(
                "Account: $prefix$sample$suffix",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Editor for a list of [RowCondition]s combined with AND semantics.
 */
@Composable
private fun RowConditionsEditor(
    conditions: List<RowCondition>,
    onConditionsChanged: (List<RowCondition>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
    title: String = "Conditions (all must match)",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        conditions.forEachIndexed { index, condition ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Condition ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onConditionsChanged(conditions.filterIndexed { i, _ -> i != index }) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove condition")
                        }
                    }
                    RowConditionRow(
                        condition = condition,
                        onConditionChanged = { updated ->
                            onConditionsChanged(conditions.mapIndexed { i, c -> if (i == index) updated else c })
                        },
                        columns = columns,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                onConditionsChanged(
                    conditions +
                        RowCondition(
                            columnName = columns.firstOrNull()?.originalName.orEmpty(),
                            operator = RowConditionOperator.EQUALS_VALUE,
                            value = "",
                        ),
                )
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Condition")
        }
    }
}

/**
 * A single editable [RowCondition]: column, operator, and the operator-dependent
 * value or other-column input.
 */
@Composable
private fun RowConditionRow(
    condition: RowCondition,
    onConditionChanged: (RowCondition) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
) {
    ColumnDropdown(
        columns = columns,
        selectedColumn = condition.columnName.takeIf { it.isNotBlank() },
        onColumnSelected = { onConditionChanged(condition.copy(columnName = it)) },
        label = "Column",
        enabled = enabled,
        isError = condition.columnName.isBlank(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    OperatorDropdown(
        selected = condition.operator,
        onSelected = { op ->
            onConditionChanged(
                when (op) {
                    RowConditionOperator.EQUALS_VALUE ->
                        condition.copy(operator = op, otherColumnName = null, value = condition.value ?: "")
                    RowConditionOperator.EQUALS_COLUMN, RowConditionOperator.NOT_EQUALS_COLUMN ->
                        condition.copy(operator = op, value = null)
                    RowConditionOperator.IS_BLANK, RowConditionOperator.IS_NOT_BLANK ->
                        condition.copy(operator = op, value = null, otherColumnName = null)
                },
            )
        },
        enabled = enabled,
    )
    when (condition.operator) {
        RowConditionOperator.EQUALS_VALUE -> {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = condition.value.orEmpty(),
                onValueChange = { onConditionChanged(condition.copy(value = it)) },
                label = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = condition.value.isNullOrBlank(),
            )
        }
        RowConditionOperator.EQUALS_COLUMN, RowConditionOperator.NOT_EQUALS_COLUMN -> {
            Spacer(modifier = Modifier.height(4.dp))
            ColumnDropdown(
                columns = columns,
                selectedColumn = condition.otherColumnName,
                onColumnSelected = { onConditionChanged(condition.copy(otherColumnName = it)) },
                label = "Other column",
                enabled = enabled,
                isError = condition.otherColumnName.isNullOrBlank(),
            )
        }
        RowConditionOperator.IS_BLANK, RowConditionOperator.IS_NOT_BLANK -> Unit
    }
}

/**
 * Dropdown selecting a [RowConditionOperator].
 */
@Composable
private fun OperatorDropdown(
    selected: RowConditionOperator,
    onSelected: (RowConditionOperator) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Operator") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RowConditionOperator.entries.forEach { op ->
                DropdownMenuItem(
                    text = { DropdownSelectionRow(text = op.label(), selected = op == selected) },
                    onClick = {
                        onSelected(op)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Editor for a single non-conditional account mapping used as a branch of a
 * [ConditionalAccountMapping]. The kind picker excludes the conditional type, so
 * nesting is bounded to one level.
 */
@Composable
private fun LeafAccountMappingEditor(
    label: String,
    mapping: FieldMapping,
    onMappingChanged: (FieldMapping) -> Unit,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    val kind =
        when (mapping) {
            is RegexAccountMapping -> LeafAccountKind.REGEX
            is TemplateAccountMapping -> LeafAccountKind.TEMPLATE
            else -> LeafAccountKind.LOOKUP
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeafAccountKind.entries.forEach { k ->
                RadioButton(
                    selected = k == kind,
                    onClick = { if (k != kind) onMappingChanged(defaultLeafAccountMapping(k, mapping.fieldType, mapping)) },
                    enabled = enabled,
                )
                Text(k.label(), modifier = Modifier.padding(end = 8.dp))
            }
        }
        when (mapping) {
            is AccountLookupMapping -> {
                ColumnDropdown(
                    columns = columns,
                    selectedColumn = mapping.columnName.takeIf { it.isNotBlank() },
                    onColumnSelected = { onMappingChanged(mapping.copy(columnName = it)) },
                    label = "Column for account name",
                    sampleValue = getSampleValue(columns, firstRow, mapping.columnName),
                    enabled = enabled,
                    isError = mapping.columnName.isBlank(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                OptionalColumnDropdown(
                    columns = columns,
                    selectedColumn = mapping.fallbackColumns.firstOrNull(),
                    onColumnSelected = { selected ->
                        onMappingChanged(mapping.copy(fallbackColumns = if (selected != null) listOf(selected) else emptyList()))
                    },
                    label = "Fallback column",
                    enabled = enabled,
                )
            }
            is RegexAccountMapping -> {
                ColumnDropdown(
                    columns = columns,
                    selectedColumn = mapping.columnName.takeIf { it.isNotBlank() },
                    onColumnSelected = { onMappingChanged(mapping.copy(columnName = it)) },
                    label = "Column for account name",
                    sampleValue = getSampleValue(columns, firstRow, mapping.columnName),
                    enabled = enabled,
                    isError = mapping.columnName.isBlank(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                RegexRulesEditor(
                    rules = mapping.rules,
                    onRulesChanged = { onMappingChanged(mapping.copy(rules = it)) },
                    columnName = mapping.columnName.takeIf { it.isNotBlank() },
                    columns = columns,
                    rows = rows,
                    enabled = enabled,
                )
            }
            is TemplateAccountMapping -> {
                TemplateAccountMappingEditor(
                    columnName = mapping.columnName.takeIf { it.isNotBlank() },
                    onColumnChanged = { onMappingChanged(mapping.copy(columnName = it)) },
                    prefix = mapping.prefix,
                    onPrefixChanged = { onMappingChanged(mapping.copy(prefix = it)) },
                    suffix = mapping.suffix,
                    onSuffixChanged = { onMappingChanged(mapping.copy(suffix = it)) },
                    columns = columns,
                    firstRow = firstRow,
                    enabled = enabled,
                )
            }
            else -> Unit
        }
    }
}

/**
 * Editor for a [ConditionalAccountMapping]: a condition list plus two nested
 * leaf-account editors for the true/false branches.
 */
@Composable
private fun ConditionalAccountMappingEditor(
    conditions: List<RowCondition>,
    onConditionsChanged: (List<RowCondition>) -> Unit,
    whenTrue: FieldMapping,
    onWhenTrueChanged: (FieldMapping) -> Unit,
    whenFalse: FieldMapping,
    onWhenFalseChanged: (FieldMapping) -> Unit,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RowConditionsEditor(
            conditions = conditions,
            onConditionsChanged = onConditionsChanged,
            columns = columns,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LeafAccountMappingEditor(
            label = "When conditions match",
            mapping = whenTrue,
            onMappingChanged = onWhenTrueChanged,
            columns = columns,
            rows = rows,
            firstRow = firstRow,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LeafAccountMappingEditor(
            label = "Otherwise",
            mapping = whenFalse,
            onMappingChanged = onWhenFalseChanged,
            columns = columns,
            rows = rows,
            firstRow = firstRow,
            enabled = enabled,
        )
    }
}

/**
 * Editor for the strategy's list of [RowPreprocessingRule]s.
 */
@Composable
private fun RowPreprocessingRulesEditor(
    rules: List<RowPreprocessingRule>,
    onRulesChanged: (List<RowPreprocessingRule>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rule ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                        }
                    }

                    fun updateRule(transform: (RowPreprocessingRule) -> RowPreprocessingRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    RowConditionsEditor(
                        conditions = rule.conditions,
                        onConditionsChanged = { updated -> updateRule { it.copy(conditions = updated) } },
                        columns = columns,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ColumnSwapsEditor(
                        swaps = rule.columnSwaps,
                        onSwapsChanged = { updated -> updateRule { it.copy(columnSwaps = updated) } },
                        columns = columns,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rule.flipSourceAndTarget,
                            onCheckedChange = { checked -> updateRule { it.copy(flipSourceAndTarget = checked) } },
                            enabled = enabled,
                        )
                        Text(
                            "Flip source and target accounts",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = { onRulesChanged(rules + RowPreprocessingRule(conditions = emptyList())) },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}

/**
 * Editor for a list of [ColumnPairSwap]s exchanged when a preprocessing rule applies.
 */
@Composable
private fun ColumnSwapsEditor(
    swaps: List<ColumnPairSwap>,
    onSwapsChanged: (List<ColumnPairSwap>) -> Unit,
    columns: List<CsvColumn>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Column swaps", style = MaterialTheme.typography.bodyMedium)
        swaps.forEachIndexed { index, swap ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ColumnDropdown(
                        columns = columns,
                        selectedColumn = swap.firstColumn.takeIf { it.isNotBlank() },
                        onColumnSelected = { selected ->
                            onSwapsChanged(swaps.mapIndexed { i, s -> if (i == index) s.copy(firstColumn = selected) else s })
                        },
                        label = "First column",
                        enabled = enabled,
                        isError = swap.firstColumn.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ColumnDropdown(
                        columns = columns,
                        selectedColumn = swap.secondColumn.takeIf { it.isNotBlank() },
                        onColumnSelected = { selected ->
                            onSwapsChanged(swaps.mapIndexed { i, s -> if (i == index) s.copy(secondColumn = selected) else s })
                        },
                        label = "Second column",
                        enabled = enabled,
                        isError = swap.secondColumn.isBlank(),
                    )
                }
                IconButton(
                    onClick = { onSwapsChanged(swaps.filterIndexed { i, _ -> i != index }) },
                    enabled = enabled,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove swap")
                }
            }
        }
        TextButton(
            onClick = {
                onSwapsChanged(swaps + ColumnPairSwap(firstColumn = "", secondColumn = ""))
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Swap")
        }
    }
}

/**
 * Editor for the strategy's list of [CompanionTransactionRule]s.
 */
@Composable
private fun CompanionTransactionRulesEditor(
    rules: List<CompanionTransactionRule>,
    onRulesChanged: (List<CompanionTransactionRule>) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rule ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                        }
                    }

                    fun updateRule(transform: (CompanionTransactionRule) -> CompanionTransactionRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    OutlinedTextField(
                        value = rule.name,
                        onValueChange = { newValue -> updateRule { it.copy(name = newValue) } },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.name.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.matchAttributeName,
                        onValueChange = { newValue -> updateRule { it.copy(matchAttributeName = newValue) } },
                        label = { Text("Match attribute name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.matchAttributeName.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.matchValuePattern,
                        onValueChange = { newValue -> updateRule { it.copy(matchValuePattern = newValue) } },
                        label = { Text("Match value pattern (SQL LIKE)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.matchValuePattern.isBlank(),
                        supportingText = { Text("e.g., ACCRUAL_CHARGE-%") },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.linkAttributeName,
                        onValueChange = { newValue -> updateRule { it.copy(linkAttributeName = newValue) } },
                        label = { Text("Link attribute name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.companionDescription,
                        onValueChange = { newValue -> updateRule { it.copy(companionDescription = newValue) } },
                        label = { Text("Companion description") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                onRulesChanged(
                    rules +
                        CompanionTransactionRule(
                            name = "",
                            matchAttributeName = "",
                            matchValuePattern = "",
                            linkAttributeName = "",
                            companionDescription = "",
                        ),
                )
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}

/**
 * Expandable multi-select checkbox list for identification columns.
 * Shows a compact summary by default with option to expand for column selection.
 */
@Composable
private fun IdentificationColumnsSelector(
    columns: List<CsvColumn>,
    selectedColumns: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val allSelected = selectedColumns.size == columns.size

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    if (allSelected) {
                        "All Columns (${columns.size})"
                    } else {
                        "${selectedColumns.size} of ${columns.size} columns"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (selectedColumns.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onSelectionChanged(columns.map { it.originalName }.toSet())
                            } else {
                                onSelectionChanged(emptySet())
                            }
                        },
                        enabled = enabled,
                    )
                    Text(
                        text = "Select All",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                columns.sortedBy { it.columnIndex }.forEach { column ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    ) {
                        Checkbox(
                            checked = column.originalName in selectedColumns,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onSelectionChanged(selectedColumns + column.originalName)
                                } else {
                                    onSelectionChanged(selectedColumns - column.originalName)
                                }
                            },
                            enabled = enabled,
                        )
                        Text(
                            text = column.originalName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dropdown selector for CSV columns with sample value preview.
 */
@Composable
private fun ColumnDropdown(
    columns: List<CsvColumn>,
    selectedColumn: String?,
    onColumnSelected: (String) -> Unit,
    label: String,
    sampleValue: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedColumn.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
            isError = isError,
            supportingText =
                sampleValue?.let {
                    { Text("Sample: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            columns.sortedBy { it.columnIndex }.forEach { column ->
                DropdownMenuItem(
                    text = {
                        DropdownSelectionRow(
                            text = column.originalName,
                            selected = column.originalName == selectedColumn,
                        )
                    },
                    onClick = {
                        onColumnSelected(column.originalName)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Optional dropdown selector for CSV columns with "None" option.
 * Allows selecting no column (null) or a specific column.
 */
@Composable
private fun OptionalColumnDropdown(
    columns: List<CsvColumn>,
    selectedColumn: String?,
    onColumnSelected: (String?) -> Unit,
    label: String,
    sampleValue: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedColumn ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
            supportingText =
                sampleValue?.let {
                    { Text("Sample: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    DropdownSelectionRow(
                        text = "None",
                        selected = selectedColumn == null,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onColumnSelected(null)
                    expanded = false
                },
            )
            columns.sortedBy { it.columnIndex }.forEach { column ->
                DropdownMenuItem(
                    text = {
                        DropdownSelectionRow(
                            text = column.originalName,
                            selected = column.originalName == selectedColumn,
                        )
                    },
                    onClick = {
                        onColumnSelected(column.originalName)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DropdownSelectionRow(
    text: String,
    selected: Boolean,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            color = textColor,
        )
        if (selected) {
            Text(
                text = "\u2713",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Dropdown selector for timezone with search capability.
 * Uses kotlinx-datetime's TimeZone.availableZoneIds for multiplatform compatibility.
 */
@Composable
private fun TimezonePicker(
    selectedTimezone: String,
    onTimezoneSelected: (String) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Get all available timezone IDs (multiplatform via kotlinx-datetime)
    val allTimezones = remember { TimeZone.availableZoneIds.sorted() }

    // Filter timezones based on search query
    val filteredTimezones =
        remember(searchQuery) {
            if (searchQuery.isBlank()) {
                // Show common timezones first when no search
                val common =
                    listOf(
                        "Europe/London",
                        "UTC",
                        "America/New_York",
                        "America/Los_Angeles",
                        "Europe/Paris",
                        "Asia/Tokyo",
                    ).filter { it in allTimezones }
                common + (allTimezones - common.toSet()).take(20)
            } else {
                allTimezones.filter { it.contains(searchQuery, ignoreCase = true) }.take(50)
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = if (expanded) searchQuery else selectedTimezone,
            onValueChange = { searchQuery = it },
            readOnly = !expanded,
            label = { Text("Select Timezone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            placeholder = { if (expanded) Text("Type to search...") },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
        ) {
            filteredTimezones.forEach { tzId ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(tzId)
                            if (tzId == selectedTimezone) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        onTimezoneSelected(tzId)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }
            if (filteredTimezones.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No timezones found", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

/**
 * Editor for attribute column mappings.
 * Allows mapping CSV columns to attribute types (existing or new).
 */
@Composable
private fun AttributeMappingsEditor(
    columns: List<CsvColumn>,
    mappings: List<AttributeColumnMapping>,
    onMappingsChanged: (List<AttributeColumnMapping>) -> Unit,
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
    firstRow: CsvRow?,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    if (mappings.isEmpty()) {
                        "None configured (click to expand)"
                    } else {
                        "${mappings.size} attribute(s) configured"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                columns.sortedBy { it.columnIndex }.forEach { column ->
                    val columnName = column.originalName
                    val mapping = mappings.find { it.columnName == columnName }
                    val isEnabled = mapping != null
                    val attributeTypeName = mapping?.attributeTypeName ?: columnName
                    val isUniqueIdentifier = mapping?.isUniqueIdentifier ?: false
                    val sampleValue =
                        firstRow
                            ?.values
                            ?.getOrNull(column.columnIndex)
                            .orEmpty()

                    AttributeColumnMappingRow(
                        columnName = columnName,
                        sampleValue = sampleValue,
                        isEnabled = isEnabled,
                        attributeTypeName = attributeTypeName,
                        isUniqueIdentifier = isUniqueIdentifier,
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = enabled,
                        onEnabledChanged = { checked ->
                            if (checked) {
                                onMappingsChanged(
                                    mappings +
                                        AttributeColumnMapping(
                                            columnName = columnName,
                                            attributeTypeName = columnName,
                                        ),
                                )
                            } else {
                                onMappingsChanged(mappings.filter { it.columnName != columnName })
                            }
                        },
                        onAttributeTypeChanged = { newTypeName ->
                            onMappingsChanged(
                                mappings.map {
                                    if (it.columnName == columnName) {
                                        it.copy(attributeTypeName = newTypeName)
                                    } else {
                                        it
                                    }
                                },
                            )
                        },
                        onUniqueIdentifierChanged = { isUnique ->
                            onMappingsChanged(
                                mappings.map {
                                    if (it.columnName == columnName) {
                                        it.copy(isUniqueIdentifier = isUnique)
                                    } else {
                                        it
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Single row for mapping a CSV column to an attribute type.
 */
@Composable
private fun AttributeColumnMappingRow(
    columnName: String,
    sampleValue: String,
    isEnabled: Boolean,
    attributeTypeName: String,
    isUniqueIdentifier: Boolean,
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onAttributeTypeChanged: (String) -> Unit,
    onUniqueIdentifierChanged: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = isEnabled,
                onCheckedChange = onEnabledChanged,
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = columnName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sampleValue.isNotBlank()) {
                    Text(
                        text = "Sample: $sampleValue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Show attribute type selector and unique identifier checkbox when enabled
        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp)) {
                AttributeTypeField(
                    value = attributeTypeName,
                    onValueChange = onAttributeTypeChanged,
                    existingTypes = existingAttributeTypes,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Unique identifier checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = isUniqueIdentifier,
                        onCheckedChange = onUniqueIdentifierChanged,
                        enabled = enabled,
                    )
                    Column {
                        Text(
                            text = "Use as unique identifier",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Detects duplicates across multiple imports",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Editor for regex rules that map column values to account names.
 * Shows a list of rules with pattern and account name inputs, plus a preview of matches.
 */
@Composable
private fun RegexRulesEditor(
    rules: List<RegexRule>,
    onRulesChanged: (List<RegexRule>) -> Unit,
    columnName: String?,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Regex Rules",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Rules are evaluated in order. First match wins. Case-insensitive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Get column index for preview
        val columnIndex =
            columnName?.let { name ->
                columns.find { it.originalName == name }?.columnIndex
            }

        // Show each rule with its matches
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rule ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                onRulesChanged(rules.filterIndexed { i, _ -> i != index })
                            },
                            enabled = enabled,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove rule",
                            )
                        }
                    }

                    OutlinedTextField(
                        value = rule.pattern,
                        onValueChange = { newPattern ->
                            onRulesChanged(
                                rules.mapIndexed { i, r ->
                                    if (i == index) r.copy(pattern = newPattern) else r
                                },
                            )
                        },
                        label = { Text("Regex Pattern") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        supportingText = { Text("e.g., .*sample.*") },
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val isAccountNameError = rule.accountName.isBlank()
                    OutlinedTextField(
                        value = rule.accountName,
                        onValueChange = { newName ->
                            onRulesChanged(
                                rules.mapIndexed { i, r ->
                                    if (i == index) r.copy(accountName = newName) else r
                                },
                            )
                        },
                        label = {
                            Text(
                                "Target Account Name *",
                                color =
                                    if (isAccountNameError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = isAccountNameError,
                        supportingText = {
                            Text(
                                if (isAccountNameError) "Required" else "Account name when pattern matches",
                                color =
                                    if (isAccountNameError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                    )

                    // Show preview of matching values
                    if (columnIndex != null && rule.pattern.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val matchingValues =
                            remember(rule.pattern, rows, columnIndex) {
                                try {
                                    val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                                    rows
                                        .mapNotNull { row ->
                                            row.values.getOrNull(columnIndex)?.takeIf { it.isNotBlank() }
                                        }.filter { regex.containsMatchIn(it) }
                                        .distinct()
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        val matchCount =
                            remember(rule.pattern, rows, columnIndex) {
                                try {
                                    val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                                    rows.count { row ->
                                        row.values.getOrNull(columnIndex)?.let { regex.containsMatchIn(it) } == true
                                    }
                                } catch (_: Exception) {
                                    0
                                }
                            }
                        if (matchCount > 0) {
                            Text(
                                "Matches $matchCount rows → \"${rule.accountName}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 100.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                matchingValues.forEach { value ->
                                    Text(
                                        "  • $value",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Text(
                                "No matches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        // Add Rule button
        TextButton(
            onClick = {
                onRulesChanged(rules + RegexRule(pattern = "", accountName = ""))
            },
            enabled = enabled,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text("Add Rule")
        }

        // Summary of unmatched rows
        if (rules.isNotEmpty() && columnIndex != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val unmatchedCount =
                remember(rules, rows, columnIndex) {
                    rows.count { row ->
                        val value = row.values.getOrNull(columnIndex)
                        if (value.isNullOrBlank()) return@count false
                        rules.none { rule ->
                            if (rule.pattern.isBlank()) return@none false
                            try {
                                val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                                regex.containsMatchIn(value)
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }
                }
            if (unmatchedCount > 0) {
                Text(
                    "Unmatched: $unmatchedCount rows → use column value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
