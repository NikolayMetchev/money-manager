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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.service.ImportParseResult
import com.moneymanager.database.service.ReferenceType
import com.moneymanager.database.service.Resolution
import com.moneymanager.database.service.UnresolvedReference
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import nl.jacobras.humanreadable.HumanReadable
import kotlin.time.Clock
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

/**
 * Target account mapping mode for CSV import.
 */
private enum class TargetAccountMode {
    DIRECT_LOOKUP,
    REGEX_MATCH,
}

/**
 * Utility object for auto-detecting likely column mappings based on column names and values.
 *
 * Detection strategy (in order of priority):
 * 1. Exact word match in column name (e.g., "Date" matches "date")
 * 2. Value-based detection (e.g., column contains date-like values)
 * 3. Substring match in column name (fallback)
 */
object ColumnDetector {
    // Name patterns ordered by specificity
    private val dateNamePatterns = listOf("date", "posted", "when", "day")
    private val timeNamePatterns = listOf("time")
    private val amountNamePatterns =
        listOf("amount", "value", "sum", "debit", "credit", "money", "price", "cost", "total")
    private val descriptionNamePatterns =
        listOf("description", "memo", "narrative", "details", "reference", "particular", "note", "remark")
    private val payeeNamePatterns =
        listOf("payee", "name", "merchant", "counterparty", "beneficiary", "vendor", "recipient", "payer", "party")
    private val currencyNamePatterns = listOf("currency", "ccy", "curr", "fx")

    // Value patterns for content-based detection
    private val dateValuePatterns =
        listOf(
            // DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY
            Regex("""\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}"""),
            // YYYY-MM-DD, YYYY/MM/DD
            Regex("""\d{4}[/\-]\d{1,2}[/\-]\d{1,2}"""),
            // Month name formats: "24 Feb 2022", "Feb 24, 2022"
            Regex("""\d{1,2}\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\w*\s+\d{2,4}""", RegexOption.IGNORE_CASE),
            Regex("""(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\w*\s+\d{1,2},?\s+\d{2,4}""", RegexOption.IGNORE_CASE),
        )

    // Time values: HH:mm or HH:mm:ss format
    private val timeValuePattern = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

    // Amount values: numbers with optional decimal, negative sign, currency symbols
    private val amountValuePattern = Regex("""^[£$€¥]?-?\d{1,3}(,\d{3})*(\.\d{1,2})?$|^-?\d+(\.\d{1,2})?$""")

    // Currency values: 3-letter ISO 4217 codes
    private val currencyValuePattern = Regex("""^[A-Z]{3}$""")

    /**
     * Checks if a column name matches a pattern as a whole word.
     */
    private fun matchesNameAsWord(
        columnName: String,
        pattern: String,
    ): Boolean {
        val regex = Regex("\\b${Regex.escape(pattern)}\\b", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(columnName)
    }

    /**
     * Checks if a value looks like a date.
     */
    private fun looksLikeDate(value: String): Boolean = dateValuePatterns.any { it.containsMatchIn(value.trim()) }

    /**
     * Checks if a value looks like an amount.
     */
    private fun looksLikeAmount(value: String): Boolean = amountValuePattern.matches(value.trim())

    /**
     * Checks if a value looks like a time (HH:mm or HH:mm:ss format).
     */
    private fun looksLikeTime(value: String): Boolean = timeValuePattern.matches(value.trim())

    /**
     * Checks if a value looks like an ISO 4217 currency code.
     */
    private fun looksLikeCurrency(value: String): Boolean = currencyValuePattern.matches(value.trim().uppercase())

    /**
     * Suggests a column based on name patterns and optionally value analysis.
     */
    private fun suggestColumn(
        columns: List<CsvColumn>,
        namePatterns: List<String>,
        sampleValues: Map<Int, String>? = null,
        valueMatcher: ((String) -> Boolean)? = null,
    ): String? {
        // First pass: exact word match in column name
        for (pattern in namePatterns) {
            val match = columns.find { matchesNameAsWord(it.originalName, pattern) }
            if (match != null) return match.originalName
        }

        // Second pass: value-based detection (if sample values provided)
        if (sampleValues != null && valueMatcher != null) {
            val match =
                columns.find { col ->
                    sampleValues[col.columnIndex]?.let { valueMatcher(it) } == true
                }
            if (match != null) return match.originalName
        }

        // Third pass: substring match in column name (fallback)
        for (pattern in namePatterns) {
            val match = columns.find { it.originalName.contains(pattern, ignoreCase = true) }
            if (match != null) return match.originalName
        }

        return null
    }

    fun suggestDateColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, dateNamePatterns, sampleValues, ::looksLikeDate)

    fun suggestAmountColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, amountNamePatterns, sampleValues, ::looksLikeAmount)

    fun suggestTimeColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, timeNamePatterns, sampleValues, ::looksLikeTime)

    fun suggestDescriptionColumn(columns: List<CsvColumn>): String? = suggestColumn(columns, descriptionNamePatterns)

    fun suggestPayeeColumn(columns: List<CsvColumn>): String? = suggestColumn(columns, payeeNamePatterns)

    fun suggestCurrencyColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, currencyNamePatterns, sampleValues, ::looksLikeCurrency)

    // Columns that are unsuitable for account name fallbacks (IDs, dates, amounts, etc.)
    private val excludedFallbackPatterns = listOf("id", "date", "time", "amount", "currency", "money")

    // Preferred fallback column names (semantic columns that describe transaction type)
    private val preferredFallbackPatterns = listOf("type", "category", "kind", "transaction type")

    /**
     * Checks if a column name should be excluded from fallback consideration.
     */
    private fun isExcludedForFallback(columnName: String): Boolean =
        excludedFallbackPatterns.any { pattern ->
            columnName.contains(pattern, ignoreCase = true)
        }

    /**
     * Checks if a column name is a preferred fallback column.
     */
    private fun isPreferredFallback(columnName: String): Boolean =
        preferredFallbackPatterns.any { pattern ->
            columnName.equals(pattern, ignoreCase = true) ||
                columnName.contains(pattern, ignoreCase = true)
        }

    /**
     * Detects fallback columns for the target account.
     * Finds rows where the primary column is blank and identifies
     * which other columns consistently have values in those rows.
     *
     * Excludes columns that are unsuitable for account names (IDs, dates, amounts)
     * and prefers semantic columns like "Type" or "Category".
     *
     * @param primaryColumn The primary column name for target account lookup
     * @param columns The available CSV columns
     * @param rows All CSV rows to analyze
     * @return List of fallback column names, ordered by preference (best first)
     */
    fun suggestFallbackColumns(
        primaryColumn: String,
        columns: List<CsvColumn>,
        rows: List<CsvRow>,
    ): List<String> {
        val primaryIndex =
            columns.find { it.originalName == primaryColumn }?.columnIndex
                ?: return emptyList()

        // Find rows where primary column is blank
        val rowsWithBlankPrimary =
            rows.filter { row ->
                row.values.getOrNull(primaryIndex)?.isBlank() == true
            }

        if (rowsWithBlankPrimary.isEmpty()) return emptyList()

        // For each other column, count how many blank-primary rows have a value
        // Exclude columns unsuitable for account names
        val candidateColumns =
            columns
                .filter { it.originalName != primaryColumn }
                .filter { !isExcludedForFallback(it.originalName) }
                .map { col ->
                    val filledCount =
                        rowsWithBlankPrimary.count { row ->
                            row.values.getOrNull(col.columnIndex)?.isNotBlank() == true
                        }
                    Triple(col.originalName, filledCount, isPreferredFallback(col.originalName))
                }
                .filter { (_, count, _) -> count > 0 }
                // Sort by: preferred columns first, then by coverage
                .sortedWith(
                    compareByDescending<Triple<String, Int, Boolean>> { (_, _, preferred) -> preferred }
                        .thenByDescending { (_, count, _) -> count },
                )
                .map { (name, _, _) -> name }

        // Return top candidate(s) - typically just the best one
        return candidateColumns.take(1)
    }
}

/**
 * Helper function to get sample value from first row for a given column.
 */
private fun getSampleValue(
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    columnName: String?,
): String? {
    if (columnName == null || firstRow == null) return null
    val columnIndex = columns.find { it.originalName == columnName }?.columnIndex ?: return null
    return firstRow.values.getOrNull(columnIndex)
}

/**
 * Finds the first row where the specified column is blank.
 * Used to find a representative sample for fallback columns.
 */
private fun findRowWithBlankColumn(
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    columnName: String?,
): CsvRow? {
    if (columnName == null) return null
    val columnIndex = columns.find { it.originalName == columnName }?.columnIndex ?: return null
    return rows.find { row ->
        row.values.getOrNull(columnIndex)?.isBlank() == true
    }
}

/**
 * Data class holding extracted form state from an existing strategy.
 */
private data class StrategyFormState(
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
    val selectedAccountId: AccountId?,
    val targetAccountColumnName: String?,
    val targetAccountFallbackColumns: List<String>,
    val targetAccountMode: TargetAccountMode,
    val regexRules: List<RegexRule>,
    val currencyMode: CurrencyMode,
    val selectedCurrencyId: CurrencyId?,
    val currencyColumnName: String?,
    val timezoneMode: TimezoneMode,
    val selectedTimezone: String,
    val timezoneColumnName: String?,
    val attributeMappings: List<AttributeColumnMapping>,
)

/**
 * Extracts form state from an existing strategy.
 * Returns null for columns that don't exist in the current CSV.
 */
private fun extractFormStateFromStrategy(
    strategy: CsvImportStrategy,
    availableColumnNames: Set<String>,
): StrategyFormState {
    // Helper to check if column exists
    fun columnIfExists(name: String?): String? = name?.takeIf { it in availableColumnNames }

    // Extract source account ID
    val sourceAccountMapping = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]
    val selectedAccountId = (sourceAccountMapping as? HardCodedAccountMapping)?.accountId

    // Extract target account column
    val targetAccountMapping = strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
    val targetAccountColumnName: String?
    val targetAccountFallbackColumns: List<String>
    val targetAccountMode: TargetAccountMode
    val regexRules: List<RegexRule>
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
    when (timestampMapping) {
        is DateTimeParsingMapping -> {
            dateColumnName = columnIfExists(timestampMapping.dateColumnName)
            dateFormat = timestampMapping.dateFormat
            timeColumnName = columnIfExists(timestampMapping.timeColumnName)
            timeFormat = timestampMapping.timeFormat ?: "HH:mm:ss"
        }
        else -> {
            dateColumnName = null
            dateFormat = "dd/MM/yyyy"
            timeColumnName = null
            timeFormat = "HH:mm:ss"
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
    when (amountMapping) {
        is AmountParsingMapping -> {
            amountColumnName = columnIfExists(amountMapping.amountColumnName)
            flipAccountsOnPositive = amountMapping.flipAccountsOnPositive
        }
        else -> {
            amountColumnName = null
            flipAccountsOnPositive = true
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
        selectedAccountId = selectedAccountId,
        targetAccountColumnName = targetAccountColumnName,
        targetAccountFallbackColumns = targetAccountFallbackColumns,
        targetAccountMode = targetAccountMode,
        regexRules = regexRules,
        currencyMode = currencyMode,
        selectedCurrencyId = selectedCurrencyId,
        currencyColumnName = currencyColumnName,
        timezoneMode = timezoneMode,
        selectedTimezone = selectedTimezone,
        timezoneColumnName = timezoneColumnName,
        attributeMappings = attributeMappings,
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
    var selectedAccountId by remember { mutableStateOf(initialState?.selectedAccountId) }
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
    var flipAccountsOnPositive by remember { mutableStateOf(initialState?.flipAccountsOnPositive ?: true) }
    // List of attribute column mappings with unique identifier flags
    var attributeMappings by remember {
        mutableStateOf(initialState?.attributeMappings.orEmpty())
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
    val regexRulesValid =
        targetAccountMode != TargetAccountMode.REGEX_MATCH ||
            (regexRules.isNotEmpty() && regexRules.all { it.accountName.isNotBlank() })

    val isFormValid =
        name.isNotBlank() &&
            identificationColumns.isNotEmpty() &&
            selectedAccountId != null &&
            targetAccountColumnName != null &&
            dateColumnName != null &&
            descriptionColumnName != null &&
            amountColumnName != null &&
            regexRulesValid &&
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

    // Auto-detect fallback columns when target column is selected
    LaunchedEffect(targetAccountColumnName, rows) {
        val primaryColumn = targetAccountColumnName
        if (primaryColumn != null && rows.isNotEmpty()) {
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
        if (primaryColumn != null && rows.isNotEmpty()) {
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
                Text("Source Account", style = MaterialTheme.typography.titleSmall)
                AccountPicker(
                    selectedAccountId = selectedAccountId,
                    onAccountSelected = { selectedAccountId = it },
                    label = "Select Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    enabled = !isSaving,
                    isError = selectedAccountId == null,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Target Account Column", style = MaterialTheme.typography.titleSmall)
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

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Account Name Mapping",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = targetAccountMode == TargetAccountMode.DIRECT_LOOKUP,
                        onClick = { targetAccountMode = TargetAccountMode.DIRECT_LOOKUP },
                        enabled = !isSaving,
                    )
                    Text("Direct Lookup", modifier = Modifier.padding(end = 16.dp))
                    RadioButton(
                        selected = targetAccountMode == TargetAccountMode.REGEX_MATCH,
                        onClick = { targetAccountMode = TargetAccountMode.REGEX_MATCH },
                        enabled = !isSaving,
                    )
                    Text("Regex Match")
                }

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
                if (timeColumnName != null) {
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

                // Calculate used columns
                val usedColumns =
                    setOfNotNull(
                        dateColumnName,
                        timeColumnName,
                        descriptionColumnName,
                        amountColumnName,
                        targetAccountColumnName,
                        currencyColumnName,
                        timezoneColumnName,
                    ) +
                        targetAccountFallbackColumns +
                        descriptionFallbackColumns

                // Show unused columns for attribute selection
                val unusedColumns = csvColumns.filter { it.originalName !in usedColumns }

                if (unusedColumns.isEmpty()) {
                    Text(
                        "All columns are used by field mappings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AttributeMappingsEditor(
                        columns = unusedColumns,
                        mappings = attributeMappings,
                        onMappingsChanged = { attributeMappings = it },
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = !isSaving,
                        firstRow = firstRow,
                    )
                }

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
                                val strategy =
                                    CsvImportStrategy(
                                        id = existingStrategy?.id ?: CsvImportStrategyId(Uuid.random()),
                                        name = name,
                                        identificationColumns = identificationColumns,
                                        fieldMappings =
                                            mapOf(
                                                TransferField.SOURCE_ACCOUNT to
                                                    HardCodedAccountMapping(
                                                        id = FieldMappingId(Uuid.random()),
                                                        fieldType = TransferField.SOURCE_ACCOUNT,
                                                        accountId = selectedAccountId!!,
                                                    ),
                                                TransferField.TARGET_ACCOUNT to
                                                    when (targetAccountMode) {
                                                        TargetAccountMode.DIRECT_LOOKUP ->
                                                            AccountLookupMapping(
                                                                id = FieldMappingId(Uuid.random()),
                                                                fieldType = TransferField.TARGET_ACCOUNT,
                                                                columnName = targetAccountColumnName!!,
                                                                fallbackColumns = targetAccountFallbackColumns,
                                                            )
                                                        TargetAccountMode.REGEX_MATCH ->
                                                            RegexAccountMapping(
                                                                id = FieldMappingId(Uuid.random()),
                                                                fieldType = TransferField.TARGET_ACCOUNT,
                                                                columnName = targetAccountColumnName!!,
                                                                rules = regexRules,
                                                                fallbackColumns = targetAccountFallbackColumns,
                                                            )
                                                    },
                                                TransferField.TIMESTAMP to
                                                    DateTimeParsingMapping(
                                                        id = FieldMappingId(Uuid.random()),
                                                        fieldType = TransferField.TIMESTAMP,
                                                        dateColumnName = dateColumnName!!,
                                                        dateFormat = dateFormat,
                                                        timeColumnName = timeColumnName,
                                                        timeFormat = timeColumnName?.let { timeFormat },
                                                    ),
                                                TransferField.DESCRIPTION to
                                                    DirectColumnMapping(
                                                        id = FieldMappingId(Uuid.random()),
                                                        fieldType = TransferField.DESCRIPTION,
                                                        columnName = descriptionColumnName!!,
                                                        fallbackColumns = descriptionFallbackColumns,
                                                    ),
                                                TransferField.AMOUNT to
                                                    AmountParsingMapping(
                                                        id = FieldMappingId(Uuid.random()),
                                                        fieldType = TransferField.AMOUNT,
                                                        mode = AmountMode.SINGLE_COLUMN,
                                                        amountColumnName = amountColumnName!!,
                                                        flipAccountsOnPositive = flipAccountsOnPositive,
                                                    ),
                                                TransferField.CURRENCY to
                                                    when (currencyMode) {
                                                        CurrencyMode.HARDCODED ->
                                                            HardCodedCurrencyMapping(
                                                                id = FieldMappingId(Uuid.random()),
                                                                fieldType = TransferField.CURRENCY,
                                                                currencyId = selectedCurrencyId!!,
                                                            )
                                                        CurrencyMode.FROM_COLUMN ->
                                                            CurrencyLookupMapping(
                                                                id = FieldMappingId(Uuid.random()),
                                                                fieldType = TransferField.CURRENCY,
                                                                columnName = currencyColumnName!!,
                                                            )
                                                    },
                                                TransferField.TIMEZONE to
                                                    when (timezoneMode) {
                                                        TimezoneMode.HARDCODED ->
                                                            HardCodedTimezoneMapping(
                                                                id = FieldMappingId(Uuid.random()),
                                                                fieldType = TransferField.TIMEZONE,
                                                                timezoneId = selectedTimezone,
                                                            )
                                                        TimezoneMode.FROM_COLUMN ->
                                                            TimezoneLookupMapping(
                                                                id = FieldMappingId(Uuid.random()),
                                                                fieldType = TransferField.TIMEZONE,
                                                                columnName = timezoneColumnName!!,
                                                            )
                                                    },
                                            ),
                                        attributeMappings = attributeMappings,
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

@Composable
fun DeleteCsvStrategyDialog(
    strategy: CsvImportStrategy,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    onDismiss: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = { Text("⚠️") },
        title = { Text("Delete Strategy?") },
        text = {
            Column {
                Text("Are you sure you want to delete the strategy \"${strategy.name}\"?")
                Text(
                    "This action cannot be undone.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                    isDeleting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            csvImportStrategyRepository.deleteStrategy(strategy.id)
                            onDismiss()
                        } catch (expected: Exception) {
                            errorMessage = "Failed to delete: ${expected.message}"
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Dialog for selecting a CSV import to use as sample data when editing a strategy.
 */
@Composable
fun SelectCsvImportDialog(
    csvImportRepository: CsvImportRepository,
    onCsvSelected: (CsvImport) -> Unit,
    onDismiss: () -> Unit,
) {
    val imports by csvImportRepository.getAllImports()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select CSV File") },
        text = {
            if (imports.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(
                            text = "No CSV files available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Import a CSV file first to use as sample data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(imports) { csvImport ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onCsvSelected(csvImport) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = csvImport.originalFileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${csvImport.rowCount} rows, ${csvImport.columnCount} columns",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Imported ${HumanReadable.timeAgo(csvImport.importTimestamp)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(column.originalName)
                            if (column.originalName == selectedColumn) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "None",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selectedColumn == null) {
                            Text(
                                "✓",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                onClick = {
                    onColumnSelected(null)
                    expanded = false
                },
            )
            columns.sortedBy { it.columnIndex }.forEach { column ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(column.originalName)
                            if (column.originalName == selectedColumn) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
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
                        firstRow?.values?.getOrNull(column.columnIndex)
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
                                            isUniqueIdentifier = false,
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
                AttributeTypeSelector(
                    selectedTypeName = attributeTypeName,
                    existingAttributeTypes = existingAttributeTypes,
                    onTypeNameChanged = onAttributeTypeChanged,
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
 * Dropdown/text field for selecting or entering an attribute type name.
 */
@Composable
private fun AttributeTypeSelector(
    selectedTypeName: String,
    existingAttributeTypes: List<AttributeType>,
    onTypeNameChanged: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var textValue by remember(selectedTypeName) { mutableStateOf(selectedTypeName) }

    // Combine existing types with filtered suggestions
    val suggestions =
        remember(textValue, existingAttributeTypes) {
            if (textValue.isBlank()) {
                existingAttributeTypes.map { it.name }
            } else {
                existingAttributeTypes
                    .map { it.name }
                    .filter { it.contains(textValue, ignoreCase = true) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                onTypeNameChanged(newValue)
                expanded = true
            },
            label = { Text("Attribute Type") },
            trailingIcon = {
                if (existingAttributeTypes.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            singleLine = true,
            supportingText = {
                if (existingAttributeTypes.isEmpty()) {
                    Text("Enter attribute type name")
                } else {
                    Text("Select existing or enter new")
                }
            },
        )

        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                suggestions.forEach { typeName ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(typeName)
                                if (typeName == selectedTypeName) {
                                    Text(
                                        "✓",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        onClick = {
                            textValue = typeName
                            onTypeNameChanged(typeName)
                            expanded = false
                        },
                    )
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
                                    rows.mapNotNull { row ->
                                        row.values.getOrNull(columnIndex)?.takeIf { it.isNotBlank() }
                                    }.filter { regex.containsMatchIn(it) }.distinct()
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

/**
 * Dialog for importing a CSV strategy from a JSON file.
 * Shows unresolved references and allows the user to map them to existing entities or create new ones.
 */
@Composable
fun ImportStrategyDialog(
    parseResult: ImportParseResult,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvStrategyExportService: CsvStrategyExportService,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    onDismiss: () -> Unit,
    onImportSuccess: () -> Unit,
) {
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val categories by categoryRepository.getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var resolutions by remember {
        mutableStateOf<Map<UnresolvedReference, Resolution>>(emptyMap())
    }
    var strategyName by remember { mutableStateOf(parseResult.strategyName) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    // Check if all unresolved references have been resolved
    val allResolved = parseResult.unresolvedReferences.all { it in resolutions.keys }

    // Check for name conflict
    val existingStrategies by csvImportStrategyRepository.getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val nameConflict = existingStrategies.any { it.name == strategyName }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = strategyName,
                    onValueChange = { strategyName = it },
                    label = { Text("Strategy Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isImporting,
                    isError = strategyName.isBlank() || nameConflict,
                    supportingText = {
                        when {
                            strategyName.isBlank() -> Text("Name is required")
                            nameConflict ->
                                Text(
                                    "A strategy with this name already exists",
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    },
                )

                if (parseResult.unresolvedReferences.isEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All references resolved. Ready to import.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Resolve Missing References",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "The following references need to be mapped or created:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    parseResult.unresolvedReferences.forEach { ref ->
                        ReferenceResolutionRow(
                            reference = ref,
                            resolution = resolutions[ref],
                            onResolutionChanged = { resolution ->
                                resolutions = resolutions + (ref to resolution)
                            },
                            accounts = accounts,
                            categories = categories,
                            currencies = currencies,
                            enabled = !isImporting,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
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
                    isImporting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            // Update the export with the new name if changed
                            val exportWithNewName = parseResult.export.copy(name = strategyName)

                            // Create the strategy
                            val strategy =
                                csvStrategyExportService.createStrategyFromExport(
                                    export = exportWithNewName,
                                    resolutions = resolutions,
                                )

                            // Save it
                            csvImportStrategyRepository.createStrategy(strategy)
                            onImportSuccess()
                            onDismiss()
                        } catch (expected: Exception) {
                            errorMessage = "Failed to import: ${expected.message}"
                            isImporting = false
                        }
                    }
                },
                enabled = allResolved && strategyName.isNotBlank() && !nameConflict && !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Row for resolving a single unresolved reference.
 */
@Composable
private fun ReferenceResolutionRow(
    reference: UnresolvedReference,
    resolution: Resolution?,
    onResolutionChanged: (Resolution) -> Unit,
    accounts: List<Account>,
    categories: List<Category>,
    currencies: List<Currency>,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var createNewName by remember { mutableStateOf(reference.name) }
    var selectedOption by remember(resolution) {
        mutableStateOf(
            when (resolution) {
                is Resolution.CreateNew -> "create"
                is Resolution.MapToExisting -> "existing:${resolution.id}"
                null -> null
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reference.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val refType = reference.type.name.lowercase().replaceFirstChar { it.uppercase() }
                    val fieldName = reference.fieldType.name.lowercase().replace("_", " ")
                    Text(
                        text = "$refType for $fieldName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (resolution != null) {
                    Text(
                        "✓",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Resolution options
            when (reference.type) {
                ReferenceType.ACCOUNT -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:").toLongOrNull()
                                        accounts.find { it.id.id == id }?.name ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new account",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(Resolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = {
                                        selectedOption = "existing:${account.id.id}"
                                        onResolutionChanged(Resolution.MapToExisting(account.id.id))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    // Show name field for create option
                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(Resolution.CreateNew(newName))
                            },
                            label = { Text("New account name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }

                ReferenceType.CATEGORY -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:").toLongOrNull()
                                        categories.find { it.id == id }?.name ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new category",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(Resolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        selectedOption = "existing:${category.id}"
                                        onResolutionChanged(Resolution.MapToExisting(category.id))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(Resolution.CreateNew(newName))
                            },
                            label = { Text("New category name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }

                ReferenceType.CURRENCY -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:")
                                        currencies.find { it.id.id.toString() == id }?.let { "${it.code} - ${it.name}" }
                                            ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new currency",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(Resolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            currencies.forEach { currency ->
                                DropdownMenuItem(
                                    text = { Text("${currency.code} - ${currency.name}") },
                                    onClick = {
                                        selectedOption = "existing:${currency.id.id}"
                                        // Currency uses UUID, so we need to handle differently
                                        // For now, store the string representation
                                        onResolutionChanged(Resolution.MapToExisting(0L)) // This won't work for currency
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(Resolution.CreateNew(newName))
                            },
                            label = { Text("New currency code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Section showing existing account mappings with edit/delete actions.
 */
@Composable
private fun AccountMappingsSection(
    mappings: List<CsvAccountMapping>,
    accounts: List<Account>,
    enabled: Boolean,
    onEditMapping: (CsvAccountMapping) -> Unit,
    onDeleteMapping: (CsvAccountMapping) -> Unit,
    onAddMapping: () -> Unit,
) {
    val accountsById = remember(accounts) { accounts.associateBy { it.id } }
    var expanded by remember { mutableStateOf(mappings.isNotEmpty()) }

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
                        "${mappings.size} mapping(s) configured"
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
                // Sort mappings alphabetically by pattern
                mappings.sortedBy { it.valuePattern.pattern.lowercase() }.forEach { mapping ->
                    val account = accountsById[mapping.accountId]
                    AccountMappingRow(
                        mapping = mapping,
                        accountName = account?.name ?: "Unknown Account",
                        enabled = enabled,
                        onEdit = { onEditMapping(mapping) },
                        onDelete = { onDeleteMapping(mapping) },
                    )
                }

                // Add button
                TextButton(
                    onClick = onAddMapping,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Add Account Mapping")
                }
            }
        }
    }
}

/**
 * Single row displaying an account mapping with edit/delete actions.
 */
@Composable
private fun AccountMappingRow(
    mapping: CsvAccountMapping,
    accountName: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Column: ${mapping.columnName}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Pattern: ${mapping.valuePattern.pattern}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "→ $accountName (ID: ${mapping.accountId.id})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Dialog for editing or creating an account mapping.
 */
@Composable
private fun AccountMappingEditorDialog(
    existingMapping: CsvAccountMapping?,
    strategyId: CsvImportStrategyId,
    accounts: List<Account>,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    onDismiss: () -> Unit,
) {
    val isEditMode = existingMapping != null
    var columnName by remember { mutableStateOf(existingMapping?.columnName.orEmpty()) }
    var patternText by remember { mutableStateOf(existingMapping?.valuePattern?.pattern.orEmpty()) }
    var selectedAccountId by remember { mutableStateOf(existingMapping?.accountId) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    val isFormValid =
        columnName.isNotBlank() &&
            patternText.isNotBlank() &&
            selectedAccountId != null

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isEditMode) "Edit Account Mapping" else "Add Account Mapping") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = columnName,
                    onValueChange = { columnName = it },
                    label = { Text("Column Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = columnName.isBlank(),
                    supportingText = { Text("CSV column to match against (e.g., Name, Payee)") },
                )

                OutlinedTextField(
                    value = patternText,
                    onValueChange = { patternText = it },
                    label = { Text("Value Pattern (Regex)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = patternText.isBlank(),
                    supportingText = { Text("Use ^value$ for exact match, or .*keyword.* for contains") },
                )

                Text("Target Account", style = MaterialTheme.typography.bodyMedium)
                AccountDropdown(
                    accounts = accounts,
                    selectedAccountId = selectedAccountId,
                    onAccountSelected = { selectedAccountId = it },
                    enabled = !isSaving,
                )

                errorMessage?.let {
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
                    if (isFormValid) {
                        isSaving = true
                        errorMessage = null
                        val accountId = selectedAccountId ?: return@TextButton
                        scope.launch {
                            try {
                                val pattern = Regex(patternText, RegexOption.IGNORE_CASE)
                                val mapping = existingMapping
                                if (mapping != null) {
                                    csvAccountMappingRepository.updateMapping(
                                        mapping.copy(
                                            columnName = columnName,
                                            valuePattern = pattern,
                                            accountId = accountId,
                                        ),
                                    )
                                } else {
                                    csvAccountMappingRepository.createMapping(
                                        strategyId = strategyId,
                                        columnName = columnName,
                                        valuePattern = pattern,
                                        accountId = accountId,
                                    )
                                }
                                onDismiss()
                            } catch (expected: IllegalArgumentException) {
                                errorMessage = "Invalid pattern: ${expected.message}"
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
                Text(if (isEditMode) "Save" else "Add")
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
}

/**
 * Simple dropdown for selecting an account.
 */
@Composable
private fun AccountDropdown(
    accounts: List<Account>,
    selectedAccountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = accounts.find { it.id == selectedAccountId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedAccount?.name ?: "Select account...",
            onValueChange = {},
            readOnly = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.sortedBy { it.name }.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onAccountSelected(account.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
