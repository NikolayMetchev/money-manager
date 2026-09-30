package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.CsvBulkUnimportPlan
import com.moneymanager.csvimporter.CsvUnimportAffectedFile
import com.moneymanager.csvimporter.CsvUnimportPlan
import com.moneymanager.csvimporter.CsvUnimportResult
import com.moneymanager.csvimporter.executeCsvBulkUnimport
import com.moneymanager.csvimporter.executeCsvUnimport
import com.moneymanager.csvimporter.planCsvBulkUnimport
import com.moneymanager.csvimporter.planCsvUnimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.components.imports.ReimportProgressIndicator
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Confirms and runs an unimport of [csvImport]: previews what the file created (and which other files'
 * rows depend on it), then deletes it all, resets the file and moves it to the Ignored tab.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun UnimportDialog(
    csvImport: CsvImport,
    csvImportRepository: CsvImportReadRepository,
    accountRepository: AccountReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: (CsvUnimportResult) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var plan by remember { mutableStateOf<CsvUnimportPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(csvImport.id) {
        try {
            plan = planCsvUnimport(csvImport.id, csvImportRepository)
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.error(expected) { "Failed to prepare unimport: ${expected.message}" }
            errorMessage = "Failed to prepare unimport: ${expected.message}"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Unimport ${csvImport.originalFileName}?") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                when (val currentPlan = plan) {
                    null ->
                        if (errorMessage == null) {
                            ReimportProgressIndicator(ImportProgress("Preparing preview"))
                        }
                    else -> {
                        if (isRunning) {
                            ReimportProgressIndicator(progress ?: ImportProgress("Starting unimport"))
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        UnimportPlanPreview(
                            fileCount = 1,
                            transferCount = currentPlan.transferIds.size,
                            tradeCount = currentPlan.tradeIds.size,
                            affectedFiles = currentPlan.affectedFiles,
                        )
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
            LoadingTextButton(
                onClick = {
                    val currentPlan = plan ?: return@LoadingTextButton
                    isRunning = true
                    errorMessage = null
                    scope.launch {
                        try {
                            onComplete(
                                executeCsvUnimport(
                                    plan = currentPlan,
                                    accountRepository = accountRepository,
                                    transactionRepository = transactionRepository,
                                    transferRelationshipRepository = transferRelationshipRepository,
                                    tradeRepository = tradeRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    onProgress = { progress = it },
                                ),
                            )
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            logger.error(expected) { "Unimport failed: ${expected.message}" }
                            errorMessage = "Unimport failed: ${expected.message}"
                            isRunning = false
                        } finally {
                            progress = null
                        }
                    }
                },
                enabled = !isRunning && plan != null,
                loading = isRunning,
                label = "Unimport",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRunning) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Confirms and runs "Unimport all" over [imports] (the Imported tab, or one strategy group of it): the
 * bulk counterpart of [UnimportDialog].
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun CsvUnimportAllDialog(
    imports: List<CsvImport>,
    csvImportRepository: CsvImportReadRepository,
    accountRepository: AccountReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: (CsvUnimportResult) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var plan by remember { mutableStateOf<CsvBulkUnimportPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(imports) {
        try {
            plan = planCsvBulkUnimport(imports.map { it.id }, csvImportRepository)
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.error(expected) { "Failed to prepare unimport: ${expected.message}" }
            errorMessage = "Failed to prepare unimport: ${expected.message}"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Unimport ${imports.size} file(s)?") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                when (val currentPlan = plan) {
                    null ->
                        if (errorMessage == null) {
                            ReimportProgressIndicator(ImportProgress("Preparing preview"))
                        }
                    else -> {
                        if (isRunning) {
                            ReimportProgressIndicator(progress ?: ImportProgress("Starting unimport"))
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        UnimportPlanPreview(
                            fileCount = currentPlan.plans.size,
                            transferCount = currentPlan.transferCount,
                            tradeCount = currentPlan.tradeCount,
                            affectedFiles = currentPlan.affectedFiles,
                        )
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
            LoadingTextButton(
                onClick = {
                    val currentPlan = plan ?: return@LoadingTextButton
                    isRunning = true
                    errorMessage = null
                    scope.launch {
                        try {
                            onComplete(
                                executeCsvBulkUnimport(
                                    plan = currentPlan,
                                    csvImportRepository = csvImportRepository,
                                    accountRepository = accountRepository,
                                    transactionRepository = transactionRepository,
                                    transferRelationshipRepository = transferRelationshipRepository,
                                    tradeRepository = tradeRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    onProgress = { progress = it },
                                ),
                            )
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            logger.error(expected) { "Unimport all failed: ${expected.message}" }
                            errorMessage = "Unimport failed: ${expected.message}"
                            isRunning = false
                        } finally {
                            progress = null
                        }
                    }
                },
                enabled = !isRunning && plan != null,
                loading = isRunning,
                label = "Unimport all",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRunning) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun UnimportPlanPreview(
    fileCount: Int,
    transferCount: Int,
    tradeCount: Int,
    affectedFiles: List<CsvUnimportAffectedFile>,
) {
    val files = if (fileCount == 1) "this file" else "these $fileCount files"
    Text(
        text =
            buildString {
                append("This deletes the $transferCount transaction(s)")
                if (tradeCount > 0) append(" and $tradeCount trade(s)")
                append(" $files created, plus any accounts they created that are left empty. ")
                append("The files are then moved to the Ignored tab; restore them there to import them again.")
            },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (affectedFiles.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text =
                "Rows in these files were matched to transactions being deleted and will be reset. " +
                    "Re-import them afterwards to restore those transactions:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        affectedFiles.forEach { file ->
            Text(
                text = "• ${file.fileName} (${file.rowIndexes.size} row(s))",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** One-line summary of a finished unimport, for the screen's status message. */
fun CsvUnimportResult.summary(fileName: String): String =
    buildString {
        append("Unimported $fileName: $deletedTransfers transaction(s)")
        if (deletedTrades > 0) append(", $deletedTrades trade(s)")
        append(" deleted")
        if (deletedEmptyAccounts.isNotEmpty()) append(", ${deletedEmptyAccounts.size} empty account(s) deleted")
        if (unhiddenTransfers > 0) append(", $unhiddenTransfers reconciled transaction(s) un-hidden")
        if (affectedFiles.isNotEmpty()) {
            append(". Re-import to restore: ")
            append(affectedFiles.joinToString { it.fileName })
        }
    }
