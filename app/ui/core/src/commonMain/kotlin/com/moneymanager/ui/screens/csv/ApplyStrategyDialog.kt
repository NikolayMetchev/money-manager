@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.csv.CsvTransferMapper
import com.moneymanager.database.csv.ImportPreparation
import com.moneymanager.database.csv.StrategyMatcher
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
fun ApplyStrategyDialog(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    csvImportRepository: CsvImportRepository,
    maintenanceService: DatabaseMaintenanceService,
    onDismiss: () -> Unit,
    onImportComplete: (Int) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository.getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var selectedStrategy by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var importPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-select matching strategy when strategies load
    LaunchedEffect(strategies, csvImport.columns) {
        if (selectedStrategy == null && strategies.isNotEmpty()) {
            val columnNames = csvImport.columns.map { it.originalName }
            val matching = StrategyMatcher.findMatchingStrategy(columnNames, strategies)
            selectedStrategy = matching ?: strategies.firstOrNull()
        }
    }

    // Prepare import preview when strategy is selected
    LaunchedEffect(selectedStrategy, rows, accounts, currencies) {
        selectedStrategy?.let { strategy ->
            if (accounts.isNotEmpty() && currencies.isNotEmpty() && rows.isNotEmpty()) {
                try {
                    val accountsByName = accounts.associateBy { it.name }
                    val currenciesById = currencies.associateBy { it.id }
                    val mapper =
                        CsvTransferMapper(
                            strategy = strategy,
                            columns = csvImport.columns,
                            existingAccounts = accountsByName,
                            existingCurrencies = currenciesById,
                        )
                    importPreparation = mapper.prepareImport(rows)
                    errorMessage = null
                } catch (e: Exception) {
                    errorMessage = "Failed to prepare import: ${e.message}"
                    importPreparation = null
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Apply Import Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                // Strategy selector
                StrategySelector(
                    strategies = strategies,
                    selectedStrategy = selectedStrategy,
                    onStrategySelected = { selectedStrategy = it },
                    csvColumns = csvImport.columns,
                    enabled = !isImporting,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Import preview
                importPreparation?.let { prep ->
                    ImportPreviewSection(prep)
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
                    val strategy = selectedStrategy ?: return@TextButton
                    val prep = importPreparation ?: return@TextButton

                    isImporting = true
                    errorMessage = null

                    scope.launch {
                        try {
                            // Create new accounts first
                            for (newAccount in prep.newAccounts) {
                                val account =
                                    Account(
                                        id = com.moneymanager.domain.model.AccountId(0),
                                        name = newAccount.name,
                                        openingDate = Clock.System.now(),
                                        categoryId = newAccount.categoryId,
                                    )
                                accountRepository.createAccount(account)
                            }

                            // Re-map with new account IDs
                            val updatedAccounts = accountRepository.getAllAccounts().first()
                            val accountsByName = updatedAccounts.associateBy { it.name }
                            val currenciesById = currencies.associateBy { it.id }

                            val mapper =
                                CsvTransferMapper(
                                    strategy = strategy,
                                    columns = csvImport.columns,
                                    existingAccounts = accountsByName,
                                    existingCurrencies = currenciesById,
                                )

                            val finalPrep = mapper.prepareImport(rows)

                            // Create transfers and track which rows they came from
                            val rowTransferMap = mutableMapOf<Long, com.moneymanager.domain.model.TransferId>()
                            var successCount = 0

                            for ((index, transfer) in finalPrep.validTransfers.withIndex()) {
                                // Find the original row index for this transfer
                                val originalRowIndex =
                                    rows.getOrNull(index)?.rowIndex
                                        ?: continue

                                transactionRepository.createTransfer(transfer)
                                rowTransferMap[originalRowIndex] = transfer.id
                                successCount++
                            }

                            // Update CSV rows with transfer IDs
                            if (rowTransferMap.isNotEmpty()) {
                                csvImportRepository.updateRowTransferIdsBatch(
                                    csvImport.id,
                                    rowTransferMap,
                                )
                            }

                            // Refresh materialized views so transfers are visible
                            maintenanceService.refreshMaterializedViews()

                            onImportComplete(successCount)
                        } catch (e: Exception) {
                            errorMessage = "Import failed: ${e.message}"
                            isImporting = false
                        }
                    }
                },
                enabled =
                    !isImporting &&
                        selectedStrategy != null &&
                        importPreparation != null &&
                        importPreparation?.validTransfers?.isNotEmpty() == true,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("Import ${importPreparation?.validTransfers?.size ?: 0} Transfers")
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

@Composable
private fun StrategySelector(
    strategies: List<CsvImportStrategy>,
    selectedStrategy: CsvImportStrategy?,
    onStrategySelected: (CsvImportStrategy) -> Unit,
    csvColumns: List<CsvColumn>,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val columnNames = csvColumns.map { it.originalName }

    Column {
        Text(
            text = "Select Strategy",
            style = MaterialTheme.typography.titleSmall,
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selectedStrategy?.name ?: "No strategy selected",
                onValueChange = {},
                readOnly = true,
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
                strategies.forEach { strategy ->
                    val isMatch = strategy.matchesColumns(columnNames.toSet())
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(strategy.name)
                                if (isMatch) {
                                    Text(
                                        text = "Match",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onStrategySelected(strategy)
                            expanded = false
                        },
                    )
                }
            }
        }

        if (strategies.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No strategies available. Create a strategy first.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ImportPreviewSection(prep: ImportPreparation) {
    Column {
        // Summary stats
        Text(
            text = "Import Preview",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCard(
                label = "Valid",
                count = prep.validTransfers.size,
                color = MaterialTheme.colorScheme.primary,
            )
            StatCard(
                label = "Errors",
                count = prep.errorRows.size,
                color = MaterialTheme.colorScheme.error,
            )
            StatCard(
                label = "New Accounts",
                count = prep.newAccounts.size,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        // New accounts to create
        if (prep.newAccounts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "New Accounts to Create:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        )
                        .padding(8.dp),
            ) {
                prep.newAccounts.forEach { account ->
                    Text(
                        text = "• ${account.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Error rows
        if (prep.errorRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Rows with Errors (will be skipped):",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.shapes.small,
                        )
                        .padding(8.dp),
            ) {
                prep.errorRows.take(5).forEach { error ->
                    Text(
                        text = "Row ${error.rowIndex}: ${error.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (prep.errorRows.size > 5) {
                    Text(
                        text = "... and ${prep.errorRows.size - 5} more errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Preview of valid transfers
        if (prep.validTransfers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transfer Preview (first 5):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TransferPreviewTable(prep.validTransfers.take(5))
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    count: Int,
    color: androidx.compose.ui.graphics.Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .background(
                    color.copy(alpha = 0.1f),
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun TransferPreviewTable(transfers: List<Transfer>) {
    val scrollState = rememberScrollState()

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
    ) {
        Column {
            // Header
            Row(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                TableCell("Date", isHeader = true)
                TableCell("Description", isHeader = true, width = 200.dp)
                TableCell("Amount", isHeader = true)
            }
            // Data rows
            transfers.forEach { transfer ->
                Row(
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    TableCell(transfer.timestamp.toString().take(10))
                    TableCell(transfer.description, width = 200.dp)
                    TableCell(transfer.amount.toDisplayValue().toString())
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    isHeader: Boolean = false,
    width: androidx.compose.ui.unit.Dp = 100.dp,
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .padding(8.dp),
    ) {
        Text(
            text = text,
            style =
                if (isHeader) {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodySmall
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
