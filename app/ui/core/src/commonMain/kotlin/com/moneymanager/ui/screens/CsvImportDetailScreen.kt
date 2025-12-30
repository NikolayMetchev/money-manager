@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.components.csv.CsvPreviewTable
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.csv.ApplyStrategyDialog
import com.moneymanager.ui.screens.csvstrategy.CreateCsvStrategyDialog
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun CsvImportDetailScreen(
    importId: CsvImportId,
    csvImportRepository: CsvImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenanceService: DatabaseMaintenanceService,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onTransferClick: ((TransferId, Boolean) -> Unit)? = null,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val import by csvImportRepository.getImport(importId)
        .collectAsStateWithSchemaErrorHandling(initial = null)
    var rows by remember { mutableStateOf<List<CsvRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showApplyStrategyDialog by remember { mutableStateOf(false) }
    var showCreateStrategyDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var importResultMessage by remember { mutableStateOf<String?>(null) }
    var failedRowIndexes by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var rowsRefreshTrigger by remember { mutableStateOf(0) }

    // Determine amount column index by looking for common amount column names
    val amountColumnIndex =
        remember(import) {
            import?.columns?.indexOfFirst { column ->
                val name = column.originalName.lowercase()
                name == "amount" || name == "value" || name == "total" ||
                    name == "debit" || name == "credit" || name.contains("amount")
            }?.takeIf { it >= 0 }
        }

    // Load rows when import is available or after import completes
    LaunchedEffect(import, rowsRefreshTrigger) {
        scope.launch {
            import?.let {
                isLoading = true
                // Load all rows - the actual row count is stored in the import metadata
                rows = csvImportRepository.getImportRows(importId, limit = it.rowCount, offset = 0)
                isLoading = false
            }
        }.join()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("< Back")
            }
            Row {
                TextButton(
                    onClick = { showCreateStrategyDialog = true },
                    enabled = !isDeleting && import != null,
                ) {
                    Text(
                        text = "Create Strategy",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { showApplyStrategyDialog = true },
                    enabled = !isDeleting && import != null && rows.isNotEmpty(),
                ) {
                    Text(
                        text = "Apply Strategy",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !isDeleting,
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Show import result message
        importResultMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            import == null && isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            import == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Import not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            else -> {
                val currentImport = import!!

                // File info header
                Column {
                    Text(
                        text = currentImport.originalFileName,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        Text(
                            text = "${currentImport.rowCount} rows",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "${currentImport.columnCount} columns",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val localDateTime =
                        currentImport.importTimestamp
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        text = "Imported: ${localDateTime.date} ${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Data table
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No data rows",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CsvPreviewTable(
                        columns = currentImport.columns,
                        rows = rows,
                        modifier = Modifier.fillMaxSize(),
                        amountColumnIndex = amountColumnIndex,
                        failedRowIndexes = failedRowIndexes,
                        onTransferClick = onTransferClick,
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Import?") },
            text = {
                Text("Are you sure you want to delete this CSV import? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleting = true
                        scope.launch {
                            csvImportRepository.deleteImport(importId)
                            showDeleteDialog = false
                            onDeleted()
                        }
                    },
                    enabled = !isDeleting,
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Apply Strategy dialog
    if (showApplyStrategyDialog && import != null) {
        ApplyStrategyDialog(
            csvImport = import!!,
            rows = rows,
            csvImportStrategyRepository = csvImportStrategyRepository,
            accountRepository = accountRepository,
            currencyRepository = currencyRepository,
            transactionRepository = transactionRepository,
            csvImportRepository = csvImportRepository,
            attributeTypeRepository = attributeTypeRepository,
            maintenanceService = maintenanceService,
            onDismiss = { showApplyStrategyDialog = false },
            onImportComplete = { result ->
                showApplyStrategyDialog = false
                failedRowIndexes = result.failedRows.map { it.rowIndex }.toSet()
                importResultMessage =
                    buildString {
                        append("Successfully imported ${result.successCount} transfers")
                        if (result.failedRows.isNotEmpty()) {
                            append("\n\nFailed rows (${result.failedRows.size}):")
                            result.failedRows.forEach { failed ->
                                append("\n  Row ${failed.rowIndex}: ${failed.errorMessage}")
                            }
                        }
                    }
                rowsRefreshTrigger++
            },
        )
    }

    // Create Strategy dialog
    if (showCreateStrategyDialog && import != null) {
        CreateCsvStrategyDialog(
            csvImportStrategyRepository = csvImportStrategyRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            attributeTypeRepository = attributeTypeRepository,
            csvColumns = import!!.columns,
            rows = rows,
            onDismiss = { showCreateStrategyDialog = false },
        )
    }
}
