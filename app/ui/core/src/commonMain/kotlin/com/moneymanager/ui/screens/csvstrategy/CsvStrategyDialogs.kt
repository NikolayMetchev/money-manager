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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
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
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
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

@Composable
fun CreateCsvStrategyDialog(
    csvImportStrategyRepository: CsvImportStrategyRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    csvColumns: List<CsvColumn>,
    rows: List<CsvRow>,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var identificationColumns by remember { mutableStateOf(csvColumns.map { it.originalName }.toSet()) }
    var dateColumnName by remember { mutableStateOf<String?>(null) }
    var dateFormat by remember { mutableStateOf("dd/MM/yyyy") }
    var timeColumnName by remember { mutableStateOf<String?>(null) }
    var timeFormat by remember { mutableStateOf("HH:mm:ss") }
    var descriptionColumnName by remember { mutableStateOf<String?>(null) }
    var descriptionFallbackColumns by remember { mutableStateOf<List<String>>(emptyList()) }
    var amountColumnName by remember { mutableStateOf<String?>(null) }
    var selectedAccountId by remember { mutableStateOf<AccountId?>(null) }
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var currencyMode by remember { mutableStateOf(CurrencyMode.HARDCODED) }
    var currencyColumnName by remember { mutableStateOf<String?>(null) }
    var timezoneMode by remember { mutableStateOf(TimezoneMode.HARDCODED) }
    var selectedTimezone by remember { mutableStateOf(TimeZone.currentSystemDefault().id) }
    var timezoneColumnName by remember { mutableStateOf<String?>(null) }
    var targetAccountColumnName by remember { mutableStateOf<String?>(null) }
    var targetAccountFallbackColumns by remember { mutableStateOf<List<String>>(emptyList()) }
    var flipAccountsOnPositive by remember { mutableStateOf(true) }
    // Map of columnName to attributeTypeName
    var attributeMappings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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

    // Use first row for sample values (backward compatible)
    val firstRow = rows.firstOrNull()

    // Build sample values map for value-based detection
    val sampleValues: Map<Int, String>? =
        firstRow?.let { row ->
            csvColumns.associate { col -> col.columnIndex to row.values.getOrNull(col.columnIndex).orEmpty() }
        }

    // Compute form validity - all required fields must be populated
    val isFormValid =
        name.isNotBlank() &&
            identificationColumns.isNotEmpty() &&
            selectedAccountId != null &&
            targetAccountColumnName != null &&
            dateColumnName != null &&
            descriptionColumnName != null &&
            amountColumnName != null &&
            when (currencyMode) {
                CurrencyMode.HARDCODED -> selectedCurrencyId != null
                CurrencyMode.FROM_COLUMN -> currencyColumnName != null
            } &&
            when (timezoneMode) {
                TimezoneMode.HARDCODED -> true // Always valid, defaults to system timezone
                TimezoneMode.FROM_COLUMN -> timezoneColumnName != null
            }

    // Auto-detect columns on first load
    LaunchedEffect(csvColumns, firstRow) {
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
        title = { Text("Create Import Strategy") },
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
                                        id = CsvImportStrategyId(Uuid.random()),
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
                                                    AccountLookupMapping(
                                                        id = FieldMappingId(Uuid.random()),
                                                        fieldType = TransferField.TARGET_ACCOUNT,
                                                        columnName = targetAccountColumnName!!,
                                                        fallbackColumns = targetAccountFallbackColumns,
                                                        createIfMissing = true,
                                                    ),
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
                                        attributeMappings =
                                            attributeMappings.map { (columnName, attributeTypeName) ->
                                                AttributeColumnMapping(
                                                    columnName = columnName,
                                                    attributeTypeName = attributeTypeName,
                                                )
                                            },
                                        createdAt = now,
                                        updatedAt = now,
                                    )
                                csvImportStrategyRepository.createStrategy(strategy)
                                onDismiss()
                            } catch (e: Exception) {
                                errorMessage = "Failed to create strategy: ${e.message}"
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
                Text("Create")
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
                        } catch (e: Exception) {
                            errorMessage = "Failed to delete: ${e.message}"
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
    mappings: Map<String, String>,
    onMappingsChanged: (Map<String, String>) -> Unit,
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
                    val isEnabled = columnName in mappings
                    val attributeTypeName = mappings[columnName] ?: columnName
                    val sampleValue =
                        firstRow?.values?.getOrNull(column.columnIndex)
                            .orEmpty()

                    AttributeColumnMappingRow(
                        columnName = columnName,
                        sampleValue = sampleValue,
                        isEnabled = isEnabled,
                        attributeTypeName = attributeTypeName,
                        existingAttributeTypes = existingAttributeTypes,
                        enabled = enabled,
                        onEnabledChanged = { checked ->
                            if (checked) {
                                onMappingsChanged(mappings + (columnName to columnName))
                            } else {
                                onMappingsChanged(mappings - columnName)
                            }
                        },
                        onAttributeTypeChanged = { newTypeName ->
                            onMappingsChanged(mappings + (columnName to newTypeName))
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
    existingAttributeTypes: List<AttributeType>,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onAttributeTypeChanged: (String) -> Unit,
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

        // Show attribute type selector when enabled
        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            AttributeTypeSelector(
                selectedTypeName = attributeTypeName,
                existingAttributeTypes = existingAttributeTypes,
                onTypeNameChanged = onAttributeTypeChanged,
                enabled = enabled,
                modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp),
            )
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
