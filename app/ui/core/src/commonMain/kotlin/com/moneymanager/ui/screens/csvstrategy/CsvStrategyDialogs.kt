@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
    private val dateNamePatterns = listOf("date", "time", "posted", "when", "day")
    private val amountNamePatterns =
        listOf("amount", "value", "sum", "debit", "credit", "money", "price", "cost", "total")
    private val descriptionNamePatterns =
        listOf("description", "memo", "narrative", "details", "reference", "particular", "note", "remark")
    private val payeeNamePatterns =
        listOf("payee", "name", "merchant", "counterparty", "beneficiary", "vendor", "recipient", "payer", "party")

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

    // Amount values: numbers with optional decimal, negative sign, currency symbols
    private val amountValuePattern = Regex("""^[£$€¥]?-?\d{1,3}(,\d{3})*(\.\d{1,2})?$|^-?\d+(\.\d{1,2})?$""")

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

    fun suggestDescriptionColumn(columns: List<CsvColumn>): String? = suggestColumn(columns, descriptionNamePatterns)

    fun suggestPayeeColumn(columns: List<CsvColumn>): String? = suggestColumn(columns, payeeNamePatterns)
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

@Composable
fun CreateCsvStrategyDialog(
    csvImportStrategyRepository: CsvImportStrategyRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    csvColumns: List<CsvColumn>,
    firstRow: CsvRow?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var identificationColumns by remember { mutableStateOf(csvColumns.map { it.originalName }.toSet()) }
    var dateColumnName by remember { mutableStateOf<String?>(null) }
    var dateFormat by remember { mutableStateOf("dd/MM/yyyy") }
    var descriptionColumnName by remember { mutableStateOf<String?>(null) }
    var amountColumnName by remember { mutableStateOf<String?>(null) }
    var selectedAccountId by remember { mutableStateOf<AccountId?>(null) }
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var targetAccountColumnName by remember { mutableStateOf<String?>(null) }
    var flipAccountsOnPositive by remember { mutableStateOf(true) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberSchemaAwareCoroutineScope()

    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    // Build sample values map for value-based detection
    val sampleValues: Map<Int, String>? =
        firstRow?.let { row ->
            csvColumns.associate { col -> col.columnIndex to (row.values.getOrNull(col.columnIndex) ?: "") }
        }

    // Auto-detect columns on first load
    LaunchedEffect(csvColumns, firstRow) {
        if (dateColumnName == null) {
            dateColumnName = ColumnDetector.suggestDateColumn(csvColumns, sampleValues)
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
                AccountDropdown(
                    accounts = accounts,
                    selectedAccountId = selectedAccountId,
                    onAccountSelected = { selectedAccountId = it },
                    enabled = !isSaving,
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
                Text("Description Column", style = MaterialTheme.typography.titleSmall)
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = descriptionColumnName,
                    onColumnSelected = { descriptionColumnName = it },
                    label = "Column containing transaction description",
                    sampleValue = getSampleValue(csvColumns, firstRow, descriptionColumnName),
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
                CurrencyDropdown(
                    currencies = currencies,
                    selectedCurrencyId = selectedCurrencyId,
                    onCurrencySelected = { selectedCurrencyId = it },
                    enabled = !isSaving,
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
                    when {
                        name.isBlank() -> errorMessage = "Strategy name is required"
                        identificationColumns.isEmpty() -> errorMessage = "At least one identification column is required"
                        selectedAccountId == null -> errorMessage = "Source account is required"
                        selectedCurrencyId == null -> errorMessage = "Currency is required"
                        dateColumnName == null -> errorMessage = "Date column is required"
                        descriptionColumnName == null -> errorMessage = "Description column is required"
                        amountColumnName == null -> errorMessage = "Amount column is required"
                        targetAccountColumnName == null -> errorMessage = "Target account column is required"
                        else -> {
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
                                                            createIfMissing = true,
                                                        ),
                                                    TransferField.TIMESTAMP to
                                                        DateTimeParsingMapping(
                                                            id = FieldMappingId(Uuid.random()),
                                                            fieldType = TransferField.TIMESTAMP,
                                                            dateColumnName = dateColumnName!!,
                                                            dateFormat = dateFormat,
                                                        ),
                                                    TransferField.DESCRIPTION to
                                                        DirectColumnMapping(
                                                            id = FieldMappingId(Uuid.random()),
                                                            fieldType = TransferField.DESCRIPTION,
                                                            columnName = descriptionColumnName!!,
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
                                                        HardCodedCurrencyMapping(
                                                            id = FieldMappingId(Uuid.random()),
                                                            fieldType = TransferField.CURRENCY,
                                                            currencyId = selectedCurrencyId!!,
                                                        ),
                                                ),
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
                    }
                },
                enabled = !isSaving,
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
 * Multi-select checkbox list for identification columns.
 */
@Composable
private fun IdentificationColumnsSelector(
    columns: List<CsvColumn>,
    selectedColumns: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    enabled: Boolean,
) {
    Column {
        columns.sortedBy { it.columnIndex }.forEach { column ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
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
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedColumn ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedAccount?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Select Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.forEach { account ->
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

@Composable
private fun CurrencyDropdown(
    currencies: List<Currency>,
    selectedCurrencyId: CurrencyId?,
    onCurrencySelected: (CurrencyId) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCurrency = currencies.find { it.id == selectedCurrencyId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedCurrency?.let { "${it.code} - ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Select Currency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            currencies.forEach { currency ->
                DropdownMenuItem(
                    text = { Text("${currency.code} - ${currency.name}") },
                    onClick = {
                        onCurrencySelected(currency.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
