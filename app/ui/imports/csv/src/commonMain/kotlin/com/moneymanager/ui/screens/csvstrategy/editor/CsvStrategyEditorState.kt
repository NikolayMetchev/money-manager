package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.isComplete
import com.moneymanager.ui.components.rules.isComplete
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

    private val config = strategy?.config

    var name by mutableStateOf(strategy?.name.orEmpty())
    var identificationColumns by
        mutableStateOf(config?.identificationColumns?.filter { it in availableColumnNames }?.toSet() ?: availableColumnNames)

    private val timestampMapping = config?.fieldMappings?.get(TransferField.TIMESTAMP) as? DateTimeParsingMapping
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
    private val descriptionMapping = config?.fieldMappings?.get(TransferField.DESCRIPTION) as? DirectColumnMapping
    var descriptionColumnName by mutableStateOf(descriptionMapping?.value?.primaryPath.takeIfPresentIn(availableColumnNames))
    var descriptionFallbackColumns by
        mutableStateOf(
            descriptionMapping
                ?.value
                ?.paths
                .orEmpty()
                .drop(1)
                .mapNotNull { it.takeIfPresentIn(availableColumnNames) },
        )
    var descriptionExtraction by mutableStateOf(descriptionMapping?.value?.extraction)

    private val amountMapping = config?.fieldMappings?.get(TransferField.AMOUNT) as? AmountParsingMapping
    var amountMode by mutableStateOf(amountMapping?.mode ?: AmountMode.SINGLE_COLUMN)
    var amountColumnName by mutableStateOf(amountMapping?.amountColumnName.takeIfPresentIn(availableColumnNames))
    var creditColumnName by mutableStateOf(amountMapping?.creditColumnName.takeIfPresentIn(availableColumnNames))
    var debitColumnName by mutableStateOf(amountMapping?.debitColumnName.takeIfPresentIn(availableColumnNames))

    // A new strategy defaults to a signed amount (positive = money in): most bank statements are.
    var direction: Direction by mutableStateOf(amountMapping?.direction ?: Direction.AmountSign())

    // A fee whose amount column the file lacks can't be read, so it is dropped; so are conditions on
    // columns the file lacks.
    var fee by mutableStateOf(
        amountMapping
            ?.fee
            ?.takeIf { it.amount.primaryPath in availableColumnNames }
            ?.let { it.copy(conditions = it.conditions.keepPresentIn(availableColumnNames)) },
    )

    // Dropped when either column is missing from the file, like the fee.
    var foreignAmount by mutableStateOf(
        amountMapping?.foreignAmount?.takeIf {
            it.amount.primaryPath in availableColumnNames && it.currency.primaryPath in availableColumnNames
        },
    )

    private val sourceMapping = config?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)

    // The credited-leg mappings that turn a row into a trade (TO_AMOUNT/TO_CURRENCY) have no widget
    // either; carried through so saving an edited strategy keeps its trade detection.
    val tradeCreditMappings: Map<TransferField, FieldMapping> =
        config?.fieldMappings.orEmpty().filterKeys { it == TransferField.TO_AMOUNT || it == TransferField.TO_CURRENCY }
    private val sourceRulesMapping = sourceMapping as? AccountRulesMapping
    var sourceAccountMode by
        mutableStateOf(if (sourceRulesMapping != null) SourceAccountMode.RULES else SourceAccountMode.FIXED_ACCOUNT)
    var selectedAccountId by mutableStateOf((sourceMapping as? HardCodedAccountMapping)?.accountId)
    var sourceRules by mutableStateOf(sourceRulesMapping?.rules.orEmpty().keepColumnsPresentIn(availableColumnNames))

    // The category given to accounts the mapping has to create. No widget yet, so it is carried
    // verbatim; rebuilding it as UNCATEGORIZED would quietly re-file every account a future import creates.
    var sourceDefaultCategoryId by mutableStateOf(sourceRulesMapping?.defaultCategoryId ?: Category.UNCATEGORIZED_ID)

    private val targetRulesMapping = config?.fieldMappings?.get(TransferField.TARGET_ACCOUNT) as? AccountRulesMapping
    var targetRules by mutableStateOf(targetRulesMapping?.rules.orEmpty().keepColumnsPresentIn(availableColumnNames))

    /** See [sourceDefaultCategoryId]. */
    var targetDefaultCategoryId by mutableStateOf(targetRulesMapping?.defaultCategoryId ?: Category.UNCATEGORIZED_ID)

    private val currencyMapping = config?.fieldMappings?.get(TransferField.CURRENCY)
    var currencyMode by
        mutableStateOf(if (currencyMapping is CurrencyLookupMapping) CurrencyMode.FROM_COLUMN else CurrencyMode.HARDCODED)
    var selectedCurrencyId by mutableStateOf((currencyMapping as? HardCodedCurrencyMapping)?.currencyId)
    var currencyColumnName by mutableStateOf(
        (currencyMapping as? CurrencyLookupMapping)?.value?.primaryPath.takeIfPresentIn(availableColumnNames),
    )

    private val timezoneMapping = config?.fieldMappings?.get(TransferField.TIMEZONE)
    var timezoneMode by
        mutableStateOf(if (timezoneMapping is TimezoneLookupMapping) TimezoneMode.FROM_COLUMN else TimezoneMode.HARDCODED)
    var selectedTimezone by
        mutableStateOf((timezoneMapping as? HardCodedTimezoneMapping)?.timezoneId ?: TimeZone.currentSystemDefault().id)
    var timezoneColumnName by mutableStateOf((timezoneMapping as? TimezoneLookupMapping)?.columnName.takeIfPresentIn(availableColumnNames))
    var timezoneAliasesText by mutableStateOf(formatAliases((timezoneMapping as? TimezoneLookupMapping)?.aliases.orEmpty()))

    var attributeMappings by mutableStateOf(config?.attributeMappings.orEmpty().filter { it.columnName in availableColumnNames })

    // Keep only preprocessing rules whose referenced columns all still exist.
    var rowPreprocessingRules by
        mutableStateOf(
            config?.rowPreprocessingRules.orEmpty().mapNotNull { rule ->
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

    var companionTransactionRules by mutableStateOf(config?.companionTransactionRules.orEmpty())
    var fileNamePattern by mutableStateOf(config?.fileNamePattern.orEmpty())

    // Set only on Excel strategies, which target a worksheet instead of a file. Carried verbatim:
    // clearing it would turn an .xlsx strategy into a plain-CSV one that no longer matches anything.
    var worksheetName by mutableStateOf(strategy?.worksheetName)

    // Funding reconciliation: match a CSV column value against an account attribute's regex tokens to
    // resolve the hidden funding account (e.g. Curve's last-4 -> the underlying card). Edited as two
    // fields and reassembled into an [AttributeAccountMatch] on save; the attribute type defaults to
    // `card-last4` but a match is only saved when a column is chosen.
    var fundingMatchColumn by mutableStateOf(config?.fundingAttributeMatch?.column)
    var fundingMatchAttributeTypeName by
        mutableStateOf(config?.fundingAttributeMatch?.attributeTypeName ?: WellKnownIds.ACCOUNT_CARD_LAST4_ATTR_TYPE_NAME)

    // Conduit every row runs through (e.g. Curve); saved only when an account name is given.
    var conduitAccountName by mutableStateOf(config?.conduit?.accountName.orEmpty())
    var conduitConditions by mutableStateOf(config?.conduit?.conditions.orEmpty())

    var contentMatchRules by mutableStateOf(config?.contentMatchRules.orEmpty())
    var crossSourceReconcileWindowSeconds by mutableStateOf(config?.crossSourceReconcileWindowSeconds)

    // Carried through verbatim (like content-match/companion rules): its column references may name
    // columns absent from the uploaded sample, but dropping them would corrupt the strategy.
    // Edited via LegGroupsEditor (Advanced tab).
    var legGroups by mutableStateOf(config?.legGroups.orEmpty())

    // Blank = an ordinary strategy; non-blank = a reconciliation source importing into shadow accounts.
    var reconciliationSourceName by mutableStateOf(config?.reconciliation?.sourceName.orEmpty())
    var reconciliationLinkablePrefix by mutableStateOf(config?.reconciliation?.linkableAccountPrefix.orEmpty())

    // "FROM=TO" pairs, comma-separated (see CsvStrategyConfig.assetCodes).
    var assetAliasesText by mutableStateOf(formatAliases(config?.assetCodes?.aliases.orEmpty()))

    // No editors of their own yet (set by built-in strategies); carried through so saving an edited
    // strategy doesn't silently drop them.
    val currencyExtraction = (currencyMapping as? CurrencyLookupMapping)?.value?.extraction
    val currencyFallbackColumns =
        (currencyMapping as? CurrencyLookupMapping)
            ?.value
            ?.paths
            ?.drop(1)
            ?.filter { it in availableColumnNames }
            .orEmpty()
    val assetSuffixesToStrip = config?.assetCodes?.stripSuffixes.orEmpty()

    // Initial primary column, used to avoid clobbering saved fallbacks on edit-mode load.
    val initialDescriptionColumnName: String? = descriptionColumnName

    private val targetAccountValid: Boolean
        get() = targetRules.isNotEmpty() && targetRules.all { it.isComplete() }

    private val sourceAccountValid: Boolean
        get() = sourceAccountMode != SourceAccountMode.RULES || (sourceRules.isNotEmpty() && sourceRules.all { it.isComplete() })

    /** Whether the columns the chosen [amountMode] requires have all been picked. */
    private val amountValid: Boolean
        get() =
            when (amountMode) {
                AmountMode.SINGLE_COLUMN -> amountColumnName != null
                AmountMode.CREDIT_DEBIT_COLUMNS -> creditColumnName != null && debitColumnName != null
            }

    private val feeValid: Boolean
        get() = fee.isComplete()

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
        get() = contentMatchRules.all { it.isComplete() }

    private val legGroupsValid: Boolean
        get() = legGroups.all { it.isComplete() }

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
        get() = !targetAccountValid || !sourceAccountValid

    private val dateTimeFormatValid: Boolean
        get() = if (dateTimeInOneColumn) dateTimeFormat.isNotBlank() else dateFormat.isNotBlank()

    /** Whether the Amount & Date tab has an unsatisfied required field. */
    val amountDateHasError: Boolean
        get() =
            !amountValid ||
                !direction.isComplete() ||
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
                !legGroupsValid

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
                sourceAccountValid &&
                dateColumnName != null &&
                dateTimeFormatValid &&
                descriptionColumnName != null &&
                amountValid &&
                direction.isComplete() &&
                feeValid &&
                rowPreprocessingValid &&
                companionRulesValid &&
                fundingMatchValid &&
                contentMatchValid &&
                legGroupsValid &&
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
