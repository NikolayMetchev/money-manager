package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import kotlinx.datetime.TimeZone

/**
 * Tabs of the CSV strategy editor screen.
 */
internal enum class EditorTab(
    val title: String,
) {
    GENERAL("General"),
    ACCOUNTS("Accounts"),
    AMOUNT_DATE("Amount & Date"),
    ADVANCED("Advanced"),
}

/**
 * Holds the full mutable editing state of the CSV strategy editor across tab switches.
 *
 * Seeded from [initial] (the form state extracted from an existing strategy) when editing, or
 * from defaults when creating. [defaultIdentificationColumns] supplies the create-mode default
 * (all CSV columns). [toFormState] mirrors the state back into an immutable [StrategyFormState]
 * so the save path runs through [buildStrategyFromFormState].
 */
internal class CsvStrategyEditorState(
    initial: StrategyFormState?,
    defaultIdentificationColumns: Set<String>,
) {
    var selectedTab by mutableStateOf(EditorTab.GENERAL)
    var isSaving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var name by mutableStateOf(initial?.name.orEmpty())
    var identificationColumns by mutableStateOf(initial?.identificationColumns ?: defaultIdentificationColumns)
    var dateColumnName by mutableStateOf(initial?.dateColumnName)
    var dateFormat by mutableStateOf(initial?.dateFormat ?: "dd/MM/yyyy")
    var timeColumnName by mutableStateOf(initial?.timeColumnName)
    var timeFormat by mutableStateOf(initial?.timeFormat ?: "HH:mm:ss")
    var dateTimeFormat by mutableStateOf(initial?.dateTimeFormat.orEmpty())

    /**
     * When true, a single column holds both the date and time ([dateTimeFormat] drives parsing);
     * when false, the date (and optional time) live in separate columns. Seeded from whether the
     * loaded strategy had a combined format.
     */
    var dateTimeInOneColumn by mutableStateOf(!initial?.dateTimeFormat.isNullOrBlank())

    // Whether the user has hand-edited a format field. While false, the editor auto-fills the format
    // from the selected column's sample values (see AmountDateTab). Editing an existing strategy
    // starts "touched" so a saved format is never silently overwritten.
    var combinedFormatTouched by mutableStateOf(initial != null)
    var dateFormatTouched by mutableStateOf(initial != null)
    var timeFormatTouched by mutableStateOf(initial != null)
    var descriptionColumnName by mutableStateOf(initial?.descriptionColumnName)
    var descriptionFallbackColumns by mutableStateOf(initial?.descriptionFallbackColumns.orEmpty())
    var amountColumnName by mutableStateOf(initial?.amountColumnName)
    var flipAccountsOnPositive by mutableStateOf(initial?.flipAccountsOnPositive ?: true)
    var feeColumnName by mutableStateOf(initial?.feeColumnName)
    var feeConditions by mutableStateOf(initial?.feeConditions.orEmpty())
    var sourceAccountMode by mutableStateOf(initial?.sourceAccountMode ?: SourceAccountMode.FIXED_ACCOUNT)
    var selectedAccountId by mutableStateOf(initial?.selectedAccountId)
    var sourceTemplateColumnName by mutableStateOf(initial?.sourceTemplateColumnName)
    var sourceTemplatePrefix by mutableStateOf(initial?.sourceTemplatePrefix.orEmpty())
    var sourceTemplateSuffix by mutableStateOf(initial?.sourceTemplateSuffix.orEmpty())
    var targetAccountColumnName by mutableStateOf(initial?.targetAccountColumnName)
    var targetAccountFallbackColumns by mutableStateOf(initial?.targetAccountFallbackColumns.orEmpty())
    var targetAccountMode by mutableStateOf(initial?.targetAccountMode ?: TargetAccountMode.DIRECT_LOOKUP)
    var regexRules by mutableStateOf(initial?.regexRules.orEmpty())
    var targetTemplateColumnName by mutableStateOf(initial?.targetTemplateColumnName)
    var targetTemplatePrefix by mutableStateOf(initial?.targetTemplatePrefix.orEmpty())
    var targetTemplateSuffix by mutableStateOf(initial?.targetTemplateSuffix.orEmpty())
    var targetConditions by mutableStateOf(initial?.targetConditions.orEmpty())
    var targetWhenTrue: FieldMapping by mutableStateOf(initial?.targetWhenTrue ?: emptyTargetAccountMapping())
    var targetWhenFalse: FieldMapping by mutableStateOf(initial?.targetWhenFalse ?: emptyTargetAccountMapping())
    var currencyMode by mutableStateOf(initial?.currencyMode ?: CurrencyMode.HARDCODED)
    var selectedCurrencyId by mutableStateOf(initial?.selectedCurrencyId)
    var currencyColumnName by mutableStateOf(initial?.currencyColumnName)
    var timezoneMode by mutableStateOf(initial?.timezoneMode ?: TimezoneMode.HARDCODED)
    var selectedTimezone by mutableStateOf(initial?.selectedTimezone ?: TimeZone.currentSystemDefault().id)
    var timezoneColumnName by mutableStateOf(initial?.timezoneColumnName)
    var attributeMappings by mutableStateOf(initial?.attributeMappings.orEmpty())
    var rowPreprocessingRules by mutableStateOf(initial?.rowPreprocessingRules.orEmpty())
    var companionTransactionRules by mutableStateOf(initial?.companionTransactionRules.orEmpty())
    var fileNamePattern by mutableStateOf(initial?.fileNamePattern.orEmpty())

    // Not yet editable in the UI; carried through so saving never drops them.
    var contentMatchRules by mutableStateOf(initial?.contentMatchRules.orEmpty())
    var crossSourceReconcileWindowSeconds by mutableStateOf(initial?.crossSourceReconcileWindowSeconds)

    // Initial primary columns, used to avoid clobbering saved fallbacks on edit-mode load.
    val initialTargetAccountColumnName: String? = initial?.targetAccountColumnName
    val initialDescriptionColumnName: String? = initial?.descriptionColumnName

    private val targetAccountValid: Boolean
        get() =
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

    private val feeValid: Boolean
        get() = feeColumnName == null || feeConditions.all { it.isComplete() }

    private val rowPreprocessingValid: Boolean
        get() =
            rowPreprocessingRules.all { rule ->
                rule.conditions.all { it.isComplete() } &&
                    rule.columnSwaps.all { it.firstColumn.isNotBlank() && it.secondColumn.isNotBlank() }
            }

    private val companionRulesValid: Boolean
        get() =
            companionTransactionRules.all {
                it.name.isNotBlank() && it.matchAttributeName.isNotBlank() && it.matchValuePattern.isNotBlank()
            }

    private val currencyValid: Boolean
        get() =
            when (currencyMode) {
                CurrencyMode.HARDCODED -> selectedCurrencyId != null
                CurrencyMode.FROM_COLUMN -> currencyColumnName != null
            }

    private val timezoneValid: Boolean
        get() =
            when (timezoneMode) {
                TimezoneMode.HARDCODED -> true // Always valid, defaults to system timezone
                TimezoneMode.FROM_COLUMN -> timezoneColumnName != null
            }

    /** Whether the General tab has an unsatisfied required field. */
    val generalHasError: Boolean
        get() = name.isBlank() || identificationColumns.isEmpty() || descriptionColumnName == null

    /** Whether the Accounts tab has an unsatisfied required field. */
    val accountsHasError: Boolean
        get() = !targetAccountValid

    private val dateTimeFormatValid: Boolean
        get() = if (dateTimeInOneColumn) dateTimeFormat.isNotBlank() else dateFormat.isNotBlank()

    /** Whether the Amount & Date tab has an unsatisfied required field. */
    val amountDateHasError: Boolean
        get() =
            amountColumnName == null ||
                dateColumnName == null ||
                !dateTimeFormatValid ||
                !feeValid ||
                !currencyValid ||
                !timezoneValid

    /** Whether the Advanced tab has an unsatisfied required field. */
    val advancedHasError: Boolean
        get() = !rowPreprocessingValid || !companionRulesValid

    fun tabHasError(tab: EditorTab): Boolean =
        when (tab) {
            EditorTab.GENERAL -> generalHasError
            EditorTab.ACCOUNTS -> accountsHasError
            EditorTab.AMOUNT_DATE -> amountDateHasError
            EditorTab.ADVANCED -> advancedHasError
        }

    /** Whether the whole form is valid and may be saved. */
    val isValid: Boolean
        get() =
            name.isNotBlank() &&
                identificationColumns.isNotEmpty() &&
                targetAccountValid &&
                dateColumnName != null &&
                dateTimeFormatValid &&
                descriptionColumnName != null &&
                amountColumnName != null &&
                feeValid &&
                rowPreprocessingValid &&
                companionRulesValid &&
                currencyValid &&
                timezoneValid

    fun toFormState(): StrategyFormState =
        StrategyFormState(
            name = name,
            identificationColumns = identificationColumns,
            dateColumnName = dateColumnName,
            dateFormat = dateFormat,
            // The two modes are mutually exclusive: a combined format ignores any separate time
            // column, so null it out to keep the saved mapping consistent with the chosen mode.
            timeColumnName = if (dateTimeInOneColumn) null else timeColumnName,
            timeFormat = timeFormat,
            descriptionColumnName = descriptionColumnName,
            descriptionFallbackColumns = descriptionFallbackColumns,
            amountColumnName = amountColumnName,
            flipAccountsOnPositive = flipAccountsOnPositive,
            feeColumnName = feeColumnName,
            feeConditions = feeConditions,
            dateTimeFormat = if (dateTimeInOneColumn) dateTimeFormat else "",
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
            contentMatchRules = contentMatchRules,
            fileNamePattern = fileNamePattern.takeIf { it.isNotBlank() },
            crossSourceReconcileWindowSeconds = crossSourceReconcileWindowSeconds,
        )
}

/**
 * Remembers a [CsvStrategyEditorState], keyed on [editKey] so it survives recompositions and tab
 * switches but is rebuilt when the edited strategy changes.
 */
@Composable
internal fun rememberCsvStrategyEditorState(
    editKey: String,
    initial: StrategyFormState?,
    defaultIdentificationColumns: Set<String>,
): CsvStrategyEditorState = remember(editKey) { CsvStrategyEditorState(initial, defaultIdentificationColumns) }
