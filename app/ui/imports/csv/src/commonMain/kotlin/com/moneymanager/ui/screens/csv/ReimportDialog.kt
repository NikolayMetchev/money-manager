@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.CsvReimportResult
import com.moneymanager.csvimporter.ReimportPlan
import com.moneymanager.csvimporter.StrategyMatcher
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Re-imports an already-imported CSV so newly added account mappings take effect retroactively:
 * shows a read-only preview of the duplicate-account merges the current mappings imply, then (on
 * confirm) merges them, re-runs the strategy over never-imported/errored rows, and deletes
 * import-created accounts left empty.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun ReimportDialog(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    csvImportRepository: CsvImportReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: (CsvReimportResult) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val passThroughAccounts by passThroughAccountRepository
        .getAll()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var strategy by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var strategyResolved by remember { mutableStateOf(false) }
    var selectedSourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var plan by remember { mutableStateOf<ReimportPlan?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Resolve the strategy that was last applied; fall back to column matching if it was deleted.
    LaunchedEffect(csvImport.id) {
        val byId =
            csvImport.lastAppliedStrategyId?.let { strategyId ->
                csvImportStrategyRepository.getStrategyById(strategyId).first()
            }
        strategy = byId
            ?: StrategyMatcher.findMatchingStrategy(
                csvImport.columns.map { it.originalName },
                csvImportStrategyRepository.getAllStrategies().first(),
            )
        strategyResolved = true
    }

    // A source-account picker is only needed to import remaining rows; merges don't use it.
    val hasUnimportedRows = rows.any { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    val needsSourcePicker = strategy?.needsSourceAccountOverride() == true && hasUnimportedRows
    val sourceReady = !needsSourcePicker || selectedSourceAccountId != null

    // Build the read-only merge preview once the inputs are ready.
    LaunchedEffect(strategy, selectedSourceAccountId, currencies, passThroughAccounts) {
        val currentStrategy = strategy ?: return@LaunchedEffect
        if (currencies.isEmpty()) return@LaunchedEffect
        try {
            plan =
                planCsvReimport(
                    csvImport = csvImport,
                    strategy = currentStrategy,
                    sourceAccountOverride = selectedSourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    relationshipRepository = transferRelationshipRepository,
                    passThroughAccounts = passThroughAccounts,
                )
            errorMessage = null
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            logger.error(expected) { "Re-import preview failed: ${expected.message}" }
            errorMessage = "Failed to prepare re-import: ${expected.message}"
            plan = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Re-import ${csvImport.originalFileName}") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                when {
                    !strategyResolved -> CircularProgressIndicator()
                    strategy == null ->
                        Text(
                            text = "No strategy matches this import. Create or restore a matching strategy first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {
                        Text(
                            text = "Strategy: ${strategy?.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (needsSourcePicker) {
                            AccountPicker(
                                selectedAccountId = selectedSourceAccountId,
                                onAccountSelected = { selectedSourceAccountId = it },
                                label = "Source Account (for not-yet-imported rows)",
                                accountRepository = accountRepository,
                                categoryRepository = categoryRepository,
                                personRepository = personRepository,
                                enabled = !isRunning,
                                isError = selectedSourceAccountId == null,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        when (val currentPlan = plan) {
                            null -> if (errorMessage == null) CircularProgressIndicator()
                            else -> ReimportPlanPreview(currentPlan, hasUnimportedRows)
                        }
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
                    val currentStrategy = strategy ?: return@LoadingTextButton
                    val currentPlan = plan ?: return@LoadingTextButton
                    isRunning = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val result =
                                executeCsvReimport(
                                    plan = currentPlan,
                                    csvImport = csvImport,
                                    strategy = currentStrategy,
                                    sourceAccountOverride = selectedSourceAccountId,
                                    currencies = currencies,
                                    accountMappingRepository = accountMappingRepository,
                                    accountRepository = accountRepository,
                                    csvImportRepository = csvImportRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    passThroughAccounts = passThroughAccounts,
                                )
                            onComplete(result)
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            logger.error(expected) { "Re-import failed: ${expected.message}" }
                            errorMessage = "Re-import failed: ${expected.message}"
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning && strategy != null && plan != null && sourceReady,
                loading = isRunning,
                label = "Re-import",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRunning,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ReimportPlanPreview(
    plan: ReimportPlan,
    hasUnimportedRows: Boolean,
) {
    Column {
        if (plan.merges.isNotEmpty()) {
            Text(
                text = "Duplicate accounts to merge:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.merges.forEach { merge ->
                Text(
                    text = "• ${merge.duplicateName} → ${merge.targetName} (${merge.transferCount} transaction(s))",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "Merges move ALL of the duplicate's transactions (from any import) and can be undone " +
                        "from the account merge history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "No duplicate accounts to merge under the current account mappings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.rewrites.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Rows to reroute through pass-through accounts:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.rewrites.forEach { rewrite ->
                Text(
                    text = "• ${rewrite.description} → ${(rewrite.conduitNames + rewrite.merchantName).joinToString(" → ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "Each row's old transaction(s) are deleted — including any manual edits made to " +
                        "them — and the row is re-imported through the conduit chain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.skipped.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Not merged:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            plan.skipped.forEach { skip ->
                Text(
                    text = "• ${skip.accountName}: ${skip.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (hasUnimportedRows) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Rows not yet imported (or in error) will also be imported using the current mappings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Accounts created by this import that end up with no transactions will be deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
