package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
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
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
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
 * Seeded straight from [strategy] when editing, or from defaults when creating; column references
 * that no longer exist in [availableColumnNames] are dropped as they are read, so stale ones never
 * survive a load and get persisted again on save. [availableColumnNames] also supplies the
 * create-mode identification-column default (all CSV columns). [buildStrategyFromEditorState] maps
 * the state back to a [CsvImportStrategy] on save.
 */
internal class CsvStrategyEditorState(
    strategy: CsvImportStrategy?,
    availableColumnNames: Set<String>,
) {
    var selectedTab by mutableStateOf(EditorTab.GENERAL)
    var isSaving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var name by mutableStateOf(strategy?.name.orEmpty())
    var identificationColumns by
        mutableStateOf(strategy?.identificationColumns?.filter { it in availableColumnNames }?.toSet() ?: availableColumnNames)

    private val timestampMapping = strategy?.fieldMappings?.get(TransferField.TIMESTAMP) as? DateTimeParsingMapping
    var dateColumnName by mutableStateOf(timestampMapping?.dateColumnName.takeIfPresentIn(availableColumnNames))
    var dateFormat by mutableStateOf(timestampMapping?.dateFormat ?: "dd/MM/yyyy")
    var timeColumnName by mutableStateOf(timestampMapping?.timeColumnName.takeIfPresentIn(availableColumnNames))
    var timeFormat by mutableStateOf(timestampMapping?.timeFormat ?: "HH:mm:ss")

    // The time stamped onto date-only rows. No widget yet, so it is carried verbatim: rebuilding it
    // from the model default would shift every timestamp of a source that chose another midpoint.
    var defaultTime by mutableStateOf(timestampMapping?.defaultTime ?: DateTimeParsingMapping.DEFAULT_TIME)

    // Keep the combined format only while its date column still exists.
    var dateTimeFormat by mutableStateOf(timestampMapping?.dateTimeFormat?.takeIf { dateColumnName != null }.orEmpty())

    /**
     * When true, a single column holds both the date and time ([dateTimeFormat] drives parsing);
     * when false, the date (and optional time) live in separate columns. Seeded from whether the
     * loaded strategy had a combined format.
     */
    var dateTimeInOneColumn by mutableStateOf(dateTimeFormat.isNotBlank())

    // Whether the user has hand-edited a format field. While false, the editor auto-fills the format
    // from the selected column's sample values (see AmountDateTab). Editing an existing strategy
    // starts "touched" so a saved format is never silently overwritten.
    var combinedFormatTouched by mutableStateOf(strategy != null)
    var dateFormatTouched by mutableStateOf(strategy != null)
    var timeFormatTouched by mutableStateOf(strategy != null)

    // The description's cleanup regex has no widget yet, so it is carried verbatim rather than
    // reconstructed — dropping it would silently import raw, untrimmed descriptions.
    private val descriptionMapping = strategy?.fieldMappings?.get(TransferField.DESCRIPTION) as? DirectColumnMapping
    var descriptionColumnName by mutableStateOf(descriptionMapping?.columnName.takeIfPresentIn(availableColumnNames))
    var descriptionFallbackColumns by
        mutableStateOf(descriptionMapping?.fallbackColumns.orEmpty().mapNotNull { it.takeIfPresentIn(availableColumnNames) })
    var descriptionExtraction by mutableStateOf(descriptionMapping?.extraction)

    private val amountMapping = strategy?.fieldMappings?.get(TransferField.AMOUNT) as? AmountParsingMapping
    var amountMode by mutableStateOf(amountMapping?.mode ?: AmountMode.SINGLE_COLUMN)
    var amountColumnName by mutableStateOf(amountMapping?.amountColumnName.takeIfPresentIn(availableColumnNames))
    var creditColumnName by mutableStateOf(amountMapping?.creditColumnName.takeIfPresentIn(availableColumnNames))
    var debitColumnName by mutableStateOf(amountMapping?.debitColumnName.takeIfPresentIn(availableColumnNames))
    var negateValues by mutableStateOf(amountMapping?.negateValues ?: false)
    var flipAccountsOnPositive by mutableStateOf(amountMapping?.flipAccountsOnPositive ?: true)
    var feeColumnName by mutableStateOf(amountMapping?.feeColumnName.takeIfPresentIn(availableColumnNames))
    var feeConditions by
        mutableStateOf(if (feeColumnName == null) emptyList() else amountMapping?.feeConditions.keepPresentIn(availableColumnNames))

    private val sourceMapping = strategy?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
    private val sourceTemplate = sourceMapping as? TemplateAccountMapping
    var sourceAccountMode by
        mutableStateOf(if (sourceTemplate != null) SourceAccountMode.TEMPLATE else SourceAccountMode.FIXED_ACCOUNT)
    var selectedAccountId by mutableStateOf((sourceMapping as? HardCodedAccountMapping)?.accountId)
    var sourceTemplateColumnName by mutableStateOf(sourceTemplate?.columnName.takeIfPresentIn(availableColumnNames))
    var sourceTemplatePrefix by mutableStateOf(sourceTemplate?.prefix.orEmpty())
    var sourceTemplateSuffix by mutableStateOf(sourceTemplate?.suffix.orEmpty())

    // The category given to accounts the mapping has to create. No widget yet, so it is carried
    // verbatim; rebuilding it as UNCATEGORIZED would quietly re-file every account a future import
    // creates. One field per side: all four target modes store it, so the value follows a mode switch.
    var sourceDefaultCategoryId by mutableStateOf(sourceTemplate?.defaultCategoryId ?: Category.UNCATEGORIZED_ID)

    private val targetMapping = strategy?.fieldMappings?.get(TransferField.TARGET_ACCOUNT)
    private val targetTemplate = targetMapping as? TemplateAccountMapping
    private val targetConditional = targetMapping as? ConditionalAccountMapping
    var targetAccountMode by
        mutableStateOf(
            when (targetMapping) {
                is RegexAccountMapping -> TargetAccountMode.REGEX_MATCH
                is AttributeMatchAccountMapping -> TargetAccountMode.ATTRIBUTE_MATCH
                is TemplateAccountMapping -> TargetAccountMode.TEMPLATE
                is ConditionalAccountMapping -> TargetAccountMode.CONDITIONAL
                else -> TargetAccountMode.DIRECT_LOOKUP
            },
        )

    // Only the column-carrying leaf modes populate the shared column/fallback fields; template and
    // conditional targets keep their columns in their own fields below.
    var targetAccountColumnName by
        mutableStateOf(
            when (targetMapping) {
                is AccountLookupMapping -> targetMapping.columnName
                is RegexAccountMapping -> targetMapping.columnName
                is AttributeMatchAccountMapping -> targetMapping.columnName
                else -> null
            }.takeIfPresentIn(availableColumnNames),
        )
    var targetAccountFallbackColumns by
        mutableStateOf(
            when (targetMapping) {
                is AccountLookupMapping -> targetMapping.fallbackColumns
                is RegexAccountMapping -> targetMapping.fallbackColumns
                else -> emptyList()
            }.mapNotNull { it.takeIfPresentIn(availableColumnNames) },
        )

    /** See [sourceDefaultCategoryId]; every target mode but the conditional one persists this. */
    var targetDefaultCategoryId by
        mutableStateOf(
            when (targetMapping) {
                is AccountLookupMapping -> targetMapping.defaultCategoryId
                is RegexAccountMapping -> targetMapping.defaultCategoryId
                is AttributeMatchAccountMapping -> targetMapping.defaultCategoryId
                is TemplateAccountMapping -> targetMapping.defaultCategoryId
                else -> Category.UNCATEGORIZED_ID
            },
        )

    // Nullable and seeded verbatim so a non-attribute target extracts/round-trips as null; the UI
    // fills in the card-last4 default only when the user actually switches into attribute-match mode.
    var targetAttributeTypeName by mutableStateOf((targetMapping as? AttributeMatchAccountMapping)?.attributeTypeName)
    var regexRules by mutableStateOf((targetMapping as? RegexAccountMapping)?.rules.orEmpty())
    var targetTemplateColumnName by mutableStateOf(targetTemplate?.columnName.takeIfPresentIn(availableColumnNames))
    var targetTemplatePrefix by mutableStateOf(targetTemplate?.prefix.orEmpty())
    var targetTemplateSuffix by mutableStateOf(targetTemplate?.suffix.orEmpty())
    var targetConditions by mutableStateOf(targetConditional?.conditions.keepPresentIn(availableColumnNames))
    var targetWhenTrue: FieldMapping by
        mutableStateOf(targetConditional?.whenTrue?.withColumnsPresentIn(availableColumnNames) ?: emptyTargetAccountMapping())
    var targetWhenFalse: FieldMapping by
        mutableStateOf(targetConditional?.whenFalse?.withColumnsPresentIn(availableColumnNames) ?: emptyTargetAccountMapping())

    private val currencyMapping = strategy?.fieldMappings?.get(TransferField.CURRENCY)
    var currencyMode by
        mutableStateOf(if (currencyMapping is CurrencyLookupMapping) CurrencyMode.FROM_COLUMN else CurrencyMode.HARDCODED)
    var selectedCurrencyId by mutableStateOf((currencyMapping as? HardCodedCurrencyMapping)?.currencyId)
    var currencyColumnName by mutableStateOf((currencyMapping as? CurrencyLookupMapping)?.columnName.takeIfPresentIn(availableColumnNames))

    private val timezoneMapping = strategy?.fieldMappings?.get(TransferField.TIMEZONE)
    var timezoneMode by
        mutableStateOf(if (timezoneMapping is TimezoneLookupMapping) TimezoneMode.FROM_COLUMN else TimezoneMode.HARDCODED)
    var selectedTimezone by
        mutableStateOf((timezoneMapping as? HardCodedTimezoneMapping)?.timezoneId ?: TimeZone.currentSystemDefault().id)
    var timezoneColumnName by mutableStateOf((timezoneMapping as? TimezoneLookupMapping)?.columnName.takeIfPresentIn(availableColumnNames))

    var attributeMappings by mutableStateOf(strategy?.attributeMappings.orEmpty().filter { it.columnName in availableColumnNames })

    // Keep only preprocessing rules whose referenced columns all still exist.
    var rowPreprocessingRules by
        mutableStateOf(
            strategy?.rowPreprocessingRules.orEmpty().mapNotNull { rule ->
                val swaps =
                    rule.columnSwaps.filter {
                        it.firstColumn in availableColumnNames && it.secondColumn in availableColumnNames
                    }
                val conditions = rule.conditions.keepPresentIn(availableColumnNames)
                rule
                    .copy(conditions = conditions, columnSwaps = swaps)
                    .takeIf { conditions.size == rule.conditions.size && swaps.size == rule.columnSwaps.size }
            },
        )

    var companionTransactionRules by mutableStateOf(strategy?.companionTransactionRules.orEmpty())
    var fileNamePattern by mutableStateOf(strategy?.fileNamePattern.orEmpty())

    // Set only on Excel strategies, which target a worksheet instead of a file. Carried verbatim:
    // clearing it would turn an .xlsx strategy into a plain-CSV one that no longer matches anything.
    var worksheetName by mutableStateOf(strategy?.worksheetName)

    // Funding reconciliation: match a CSV column value against an account attribute's regex tokens to
    // resolve the hidden funding account (e.g. Curve's last-4 -> the underlying card). Edited as two
    // fields and reassembled into an [AttributeAccountMatch] on save; the attribute type defaults to
    // `card-last4` but a match is only saved when a column is chosen.
    var fundingMatchColumn by mutableStateOf(strategy?.fundingAttributeMatch?.column)
    var fundingMatchAttributeTypeName by
        mutableStateOf(strategy?.fundingAttributeMatch?.attributeTypeName ?: WellKnownIds.ACCOUNT_CARD_LAST4_ATTR_TYPE_NAME)

    var contentMatchRules by mutableStateOf(strategy?.contentMatchRules.orEmpty())
    var crossSourceReconcileWindowSeconds by mutableStateOf(strategy?.crossSourceReconcileWindowSeconds)

    // Carried through verbatim (like content-match/companion rules): its column references may name
    // columns absent from the uploaded sample, but dropping them would corrupt the strategy.
    // Edited via ConversionConfigEditor (Advanced tab); null when the source has no such conversions.
    var conversionConfig by mutableStateOf(strategy?.conversionConfig)

    // Initial primary columns, used to avoid clobbering saved fallbacks on edit-mode load.
    val initialTargetAccountColumnName: String? = targetAccountColumnName
    val initialDescriptionColumnName: String? = descriptionColumnName

    private val targetAccountValid: Boolean
        get() =
            when (targetAccountMode) {
                TargetAccountMode.DIRECT_LOOKUP -> targetAccountColumnName != null
                TargetAccountMode.REGEX_MATCH ->
                    targetAccountColumnName != null &&
                        regexRules.isNotEmpty() &&
                        regexRules.all { it.accountName.isNotBlank() }
                TargetAccountMode.ATTRIBUTE_MATCH ->
                    targetAccountColumnName != null && !targetAttributeTypeName.isNullOrBlank()
                TargetAccountMode.TEMPLATE -> targetTemplateColumnName != null
                TargetAccountMode.CONDITIONAL ->
                    targetConditions.isNotEmpty() &&
                        targetConditions.all { it.isComplete() } &&
                        targetWhenTrue.isLeafAccountValid() &&
                        targetWhenFalse.isLeafAccountValid()
            }

    /** Whether the columns the chosen [amountMode] requires have all been picked. */
    private val amountValid: Boolean
        get() =
            when (amountMode) {
                AmountMode.SINGLE_COLUMN -> amountColumnName != null
                AmountMode.CREDIT_DEBIT_COLUMNS -> creditColumnName != null && debitColumnName != null
            }

    private val feeValid: Boolean
        get() = feeColumnName == null || feeConditions.all { it.isComplete() }

    // A funding match is opt-in: valid when no column is chosen, otherwise its attribute type must be set.
    private val fundingMatchValid: Boolean
        get() = fundingMatchColumn.isNullOrBlank() || fundingMatchAttributeTypeName.isNotBlank()

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

    private val contentMatchValid: Boolean
        get() = contentMatchRules.all { it.columnName.isNotBlank() && it.pattern.isNotBlank() }

    // A conversion config is opt-in: valid when absent, otherwise its required scalars must be set, at
    // least one of name/rules must resolve, and every routing rule must be fully specified.
    private val conversionConfigValid: Boolean
        get() =
            conversionConfig?.let { c ->
                c.signalColumn.isNotBlank() &&
                    c.debitPattern.isNotBlank() &&
                    c.creditPattern.isNotBlank() &&
                    c.relationshipTypeName.isNotBlank() &&
                    (!c.conversionAccountName.isNullOrBlank() || c.conversionAccountRules.isNotEmpty()) &&
                    c.conversionAccountRules.all {
                        it.column.isNotBlank() && it.pattern.isNotBlank() && it.accountName.isNotBlank()
                    }
            } ?: true

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
            !amountValid ||
                dateColumnName == null ||
                !dateTimeFormatValid ||
                !feeValid ||
                !currencyValid ||
                !timezoneValid

    /** Whether the Advanced tab has an unsatisfied required field. */
    val advancedHasError: Boolean
        get() =
            !rowPreprocessingValid ||
                !companionRulesValid ||
                !fundingMatchValid ||
                !contentMatchValid ||
                !conversionConfigValid

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
                amountValid &&
                feeValid &&
                rowPreprocessingValid &&
                companionRulesValid &&
                fundingMatchValid &&
                contentMatchValid &&
                conversionConfigValid &&
                currencyValid &&
                timezoneValid
}

/**
 * Remembers a [CsvStrategyEditorState], keyed on [editKey] so it survives recompositions and tab
 * switches but is rebuilt when the edited strategy changes.
 */
@Composable
internal fun rememberCsvStrategyEditorState(
    editKey: String,
    strategy: CsvImportStrategy?,
    availableColumnNames: Set<String>,
): CsvStrategyEditorState = remember(editKey) { CsvStrategyEditorState(strategy, availableColumnNames) }
