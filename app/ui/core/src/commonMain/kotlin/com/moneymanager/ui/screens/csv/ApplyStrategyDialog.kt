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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
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
import com.moneymanager.database.CsvImportSourceRecorder
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.csv.CsvTransferMapper
import com.moneymanager.database.csv.ImportPreparation
import com.moneymanager.database.csv.StrategyMatcher
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val logger = logging()

/**
 * Result of a CSV import operation.
 */
data class CsvImportResult(
    val successCount: Int,
    val failedRows: List<FailedRow>,
) {
    data class FailedRow(
        val rowIndex: Long,
        val errorMessage: String,
    )
}

@Composable
fun ApplyStrategyDialog(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    csvImportRepository: CsvImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenanceService: DatabaseMaintenanceService,
    transferSourceQueries: TransferSourceQueries,
    deviceRepository: DeviceRepository,
    onDismiss: () -> Unit,
    onImportComplete: (CsvImportResult) -> Unit,
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

    // Filter to only show rows that will be processed: ERROR status or no status (never processed)
    val rowsToProcess =
        rows.filter { row ->
            row.importStatus == null || row.importStatus == ImportStatus.ERROR
        }

    // Prepare import preview when strategy is selected
    LaunchedEffect(selectedStrategy, rowsToProcess, accounts, currencies) {
        selectedStrategy?.let { strategy ->
            if (accounts.isNotEmpty() && currencies.isNotEmpty() && rowsToProcess.isNotEmpty()) {
                try {
                    val accountsByName = accounts.associateBy { it.name }
                    val currenciesById = currencies.associateBy { it.id }
                    val currenciesByCode = currencies.associateBy { it.code.uppercase() }
                    val mapper =
                        CsvTransferMapper(
                            strategy = strategy,
                            columns = csvImport.columns,
                            existingAccounts = accountsByName,
                            existingCurrencies = currenciesById,
                            existingCurrenciesByCode = currenciesByCode,
                        )
                    importPreparation = mapper.prepareImport(rowsToProcess)
                    errorMessage = null
                } catch (expected: Exception) {
                    errorMessage = "Failed to prepare import: ${expected.message}"
                    importPreparation = null
                }
            } else if (rowsToProcess.isEmpty() && rows.isNotEmpty()) {
                // All rows already processed successfully
                errorMessage = "All rows have already been imported successfully."
                importPreparation = null
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
                            logger.info { "Starting CSV import with ${prep.validTransfers.size} valid transfers" }

                            // Create new accounts first (skip failures - transfers using them will fail later)
                            for (newAccount in prep.newAccounts) {
                                try {
                                    val account =
                                        Account(
                                            id = com.moneymanager.domain.model.AccountId(0),
                                            name = newAccount.name,
                                            openingDate = Clock.System.now(),
                                            categoryId = newAccount.categoryId,
                                        )
                                    accountRepository.createAccount(account)
                                    logger.info { "Created new account: ${newAccount.name}" }
                                } catch (expected: Exception) {
                                    logger.warn(expected) { "Skipping account '${newAccount.name}': ${expected.message}" }
                                }
                            }

                            // Re-map with new account IDs
                            logger.info { "Re-mapping transfers with updated account IDs" }
                            val updatedAccounts = accountRepository.getAllAccounts().first()
                            val accountsByName = updatedAccounts.associateBy { it.name }
                            val currenciesById = currencies.associateBy { it.id }
                            val currenciesByCode = currencies.associateBy { it.code.uppercase() }

                            // Fetch existing transfers for duplicate detection
                            // Only fetch transfers that overlap with the CSV's date range and accounts
                            logger.info { "Determining date range and accounts for duplicate detection" }
                            val allAccountIds =
                                prep.validTransfers
                                    .flatMap { listOf(it.transfer.sourceAccountId, it.transfer.targetAccountId) }
                                    .toSet()
                            val minTimestamp =
                                prep.validTransfers.minOfOrNull { it.transfer.timestamp }
                                    ?: Clock.System.now()
                            val maxTimestamp =
                                prep.validTransfers.maxOfOrNull { it.transfer.timestamp }
                                    ?: Clock.System.now()

                            logger.info {
                                "Fetching existing transfers: ${allAccountIds.size} accounts, " +
                                    "date range $minTimestamp to $maxTimestamp"
                            }

                            // Fetch existing transfers within the CSV's date range that involve any of the CSV's accounts
                            val existingTransfers =
                                allAccountIds.flatMap { accountId ->
                                    transactionRepository.getTransactionsByAccountAndDateRange(
                                        accountId = accountId,
                                        startDate = minTimestamp,
                                        endDate = maxTimestamp,
                                    ).first()
                                }.distinctBy { it.id }

                            val existingTransferInfoList =
                                existingTransfers.map { transfer ->
                                    // Build attribute map (typeName -> value)
                                    val attributesList =
                                        transfer.attributes.map { attr ->
                                            attr.attributeType.name to attr.value
                                        }

                                    // Build unique identifier values map based on strategy
                                    val uniqueIdValues =
                                        strategy.attributeMappings
                                            .filter { it.isUniqueIdentifier }
                                            .associate { mapping ->
                                                val attributeValue =
                                                    transfer.attributes
                                                        .firstOrNull { it.attributeType.name == mapping.attributeTypeName }
                                                        ?.value.orEmpty()
                                                mapping.columnName to attributeValue
                                            }

                                    com.moneymanager.database.csv.ExistingTransferInfo(
                                        transferId = transfer.id,
                                        transfer = transfer,
                                        attributes = attributesList,
                                        uniqueIdentifierValues = uniqueIdValues,
                                    )
                                }
                            logger.info { "Loaded ${existingTransferInfoList.size} existing transfers for duplicate detection" }

                            val mapper =
                                CsvTransferMapper(
                                    strategy = strategy,
                                    columns = csvImport.columns,
                                    existingAccounts = accountsByName,
                                    existingCurrencies = currenciesById,
                                    existingCurrenciesByCode = currenciesByCode,
                                    existingTransfers = existingTransferInfoList,
                                )

                            // Handle case when all rows are already processed
                            // (rowsToProcess is already filtered at the top of the composable)
                            if (rowsToProcess.isEmpty()) {
                                logger.info { "No rows to process - all rows already imported" }
                                onImportComplete(
                                    CsvImportResult(
                                        successCount = 0,
                                        failedRows = emptyList(),
                                    ),
                                )
                                return@launch
                            }

                            val finalPrep = mapper.prepareImport(rowsToProcess)
                            val validCount = finalPrep.validTransfers.size
                            val errorCount = finalPrep.errorRows.size
                            logger.info { "Prepared $validCount valid transfers, $errorCount error rows" }

                            // Mark mapping errors as ERROR status in database and save error messages
                            for (errorRow in finalPrep.errorRows) {
                                csvImportRepository.updateRowStatus(
                                    csvImport.id,
                                    errorRow.rowIndex,
                                    ImportStatus.ERROR.name,
                                    null,
                                )
                                csvImportRepository.saveError(
                                    csvImport.id,
                                    errorRow.rowIndex,
                                    errorRow.errorMessage,
                                )
                            }

                            // Pre-resolve attribute types
                            val allAttributeTypeNames =
                                finalPrep.validTransfers
                                    .flatMap { it.attributes }
                                    .map { it.first }
                                    .toSet()
                            val attributeTypeIdByName =
                                allAttributeTypeNames.associateWith { name ->
                                    attributeTypeRepository.getOrCreate(name)
                                }

                            // Create transfers and track which rows they came from
                            logger.info { "Starting to create $validCount transfers" }
                            val rowTransferMap = mutableMapOf<Long, com.moneymanager.domain.model.TransferId>()
                            val failedRows = mutableListOf<CsvImportResult.FailedRow>()
                            var successCount = 0
                            val deviceId = deviceRepository.getOrCreateDevice(getDeviceInfo())

                            for (transferWithAttrs in finalPrep.validTransfers) {
                                val transfer = transferWithAttrs.transfer
                                val importStatus = transferWithAttrs.importStatus
                                val existingTransferId = transferWithAttrs.existingTransferId
                                val originalRowIndex = transferWithAttrs.rowIndex

                                try {
                                    // Convert attributes from (typeName, value) to NewAttribute
                                    val attributes =
                                        transferWithAttrs.attributes.mapNotNull { (typeName, value) ->
                                            val typeId = attributeTypeIdByName[typeName]
                                            if (typeId != null) NewAttribute(typeId, value) else null
                                        }

                                    when (importStatus) {
                                        ImportStatus.IMPORTED -> {
                                            // Create new transfer
                                            transactionRepository.createTransfers(
                                                transfers = listOf(transfer),
                                                newAttributes = mapOf(transfer.id to attributes),
                                                sourceRecorder =
                                                    CsvImportSourceRecorder(
                                                        queries = transferSourceQueries,
                                                        deviceId = deviceId,
                                                        csvImportId = csvImport.id,
                                                        rowIndexForTransfer = { originalRowIndex },
                                                    ),
                                            )
                                            // Update row with status and transfer ID
                                            csvImportRepository.updateRowStatus(
                                                csvImport.id,
                                                originalRowIndex,
                                                ImportStatus.IMPORTED.name,
                                                transfer.id,
                                            )
                                            // Clear any previous error for this row (re-import success)
                                            csvImportRepository.clearError(csvImport.id, originalRowIndex)
                                            rowTransferMap[originalRowIndex] = transfer.id
                                            successCount++
                                        }
                                        ImportStatus.DUPLICATE -> {
                                            // Skip creating transfer, just update CSV row status
                                            csvImportRepository.updateRowStatus(
                                                csvImport.id,
                                                originalRowIndex,
                                                ImportStatus.DUPLICATE.name,
                                                existingTransferId,
                                            )
                                            logger.info { "Skipped duplicate row $originalRowIndex" }
                                        }
                                        ImportStatus.UPDATED -> {
                                            // Update existing transfer
                                            if (existingTransferId != null) {
                                                transactionRepository.updateTransfer(
                                                    transfer = transfer.copy(id = existingTransferId),
                                                    deletedAttributeIds = emptySet(),
                                                    updatedAttributes = emptyMap(),
                                                    newAttributes = attributes,
                                                    transactionId = existingTransferId,
                                                )
                                                csvImportRepository.updateRowStatus(
                                                    csvImport.id,
                                                    originalRowIndex,
                                                    ImportStatus.UPDATED.name,
                                                    existingTransferId,
                                                )
                                                // Clear any previous error for this row (re-import success)
                                                csvImportRepository.clearError(csvImport.id, originalRowIndex)
                                                successCount++
                                            }
                                        }
                                        ImportStatus.ERROR -> {
                                            // ERROR status shouldn't come from mapper, but handle gracefully
                                            logger.warn { "Unexpected ERROR status for row $originalRowIndex" }
                                        }
                                    }
                                } catch (expected: Exception) {
                                    // Mark row as ERROR in database
                                    csvImportRepository.updateRowStatus(
                                        csvImport.id,
                                        originalRowIndex,
                                        ImportStatus.ERROR.name,
                                        null,
                                    )
                                    // Log the error and continue with remaining rows
                                    val errorMsg = expected.message ?: "Unknown error"
                                    // Persist the error message for later viewing
                                    csvImportRepository.saveError(csvImport.id, originalRowIndex, errorMsg)
                                    logger.warn(expected) {
                                        "Failed to import row $originalRowIndex: $errorMsg"
                                    }
                                    failedRows.add(
                                        CsvImportResult.FailedRow(
                                            rowIndex = originalRowIndex,
                                            errorMessage = errorMsg,
                                        ),
                                    )
                                }
                            }

                            logger.info { "Transfer creation complete: $successCount successes, ${failedRows.size} failures" }

                            // Note: CSV rows are updated with status and transfer IDs during import loop above

                            // Refresh materialized views so transfers are visible
                            logger.info { "Refreshing materialized views" }
                            maintenanceService.refreshMaterializedViews()
                            logger.info { "Import completed successfully" }

                            val result =
                                CsvImportResult(
                                    successCount = successCount,
                                    failedRows = failedRows,
                                )

                            if (successCount == 0 && failedRows.isNotEmpty()) {
                                // All rows failed - show error
                                errorMessage =
                                    "Import failed: all ${failedRows.size} rows failed due to database constraints"
                                isImporting = false
                            } else {
                                // At least some rows succeeded (or no rows at all)
                                if (failedRows.isNotEmpty()) {
                                    logger.info {
                                        "Import completed with $successCount successes and ${failedRows.size} failures"
                                    }
                                }
                                onImportComplete(result)
                            }
                        } catch (expected: Exception) {
                            logger.error(expected) { "Import failed: ${expected.message}" }
                            errorMessage = "Import failed: ${expected.message}"
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
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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

        // Status breakdown if available
        if (prep.statusCounts.isNotEmpty()) {
            Text(
                text = "Status Breakdown:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                prep.statusCounts[ImportStatus.IMPORTED]?.let { count ->
                    StatCard(
                        label = "New",
                        count = count,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                prep.statusCounts[ImportStatus.DUPLICATE]?.let { count ->
                    StatCard(
                        label = "Duplicate",
                        count = count,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                prep.statusCounts[ImportStatus.UPDATED]?.let { count ->
                    StatCard(
                        label = "Updated",
                        count = count,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

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
            TransferPreviewTable(prep.validTransfers.take(5).map { it.transfer })
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
