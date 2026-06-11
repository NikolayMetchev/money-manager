package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.moneymanager.database.csv.StrategyMatcher
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.importer.ImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies the matching strategy to every unimported CSV file in one go. Each file's strategy is
 * auto-matched from its column headers (files with no match are skipped). The shared source account
 * chosen here is used only for files whose strategy needs a user-chosen source; strategies that
 * hard-code or per-row-resolve the source ignore it. Payee accounts auto-create with detected names.
 * Mirrors QifImportAllDialog by design (CSV has no currency choice — currency comes from the strategy).
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun CsvImportAllDialog(
    unimported: List<CsvImport>,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    csvImportRepository: CsvImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository.getAllStrategies().collectAsStateWithSchemaErrorHandling(emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(emptyList())

    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    // Match each file's strategy by its columns, so we can tell how many files will be skipped (no
    // matching strategy) and whether any matched strategy needs a user-chosen source account.
    // getAllImports() doesn't populate columns, so load each file's columns before matching.
    var matchedStrategies by remember { mutableStateOf<List<CsvImportStrategy?>?>(null) }
    LaunchedEffect(unimported, strategies) {
        if (strategies.isEmpty()) {
            matchedStrategies = null
            return@LaunchedEffect
        }
        matchedStrategies =
            unimported.map { listedImport ->
                val fullImport = csvImportRepository.getImport(listedImport.id).first()
                val columnNames = fullImport?.columns.orEmpty().map { it.originalName }
                StrategyMatcher.findMatchingStrategy(columnNames, strategies)
            }
    }
    val matches = matchedStrategies
    val skippedNoStrategyCount = if (matches == null) 0 else unimported.size - matches.count { it != null }
    val needsSourceAccount = matches?.any { it != null && it.needsSourceAccountOverride() } ?: false

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(if (summary != null) "Import complete" else "Import all unimported files") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentSummary = summary
                if (currentSummary != null) {
                    Text(currentSummary, style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (needsSourceAccount) {
                        AccountPicker(
                            selectedAccountId = sourceAccountId,
                            onAccountSelected = { sourceAccountId = it },
                            label = "Source account (for files whose strategy needs one)",
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            personAccountOwnershipRepository = personAccountOwnershipRepository,
                            entitySource = entitySource,
                            enabled = !isImporting,
                            isError = sourceAccountId == null,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (skippedNoStrategyCount > 0) {
                        Text(
                            text =
                                "$skippedNoStrategyCount file${if (skippedNoStrategyCount == 1) "" else "s"} " +
                                    "have no matching strategy and will be skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    progress?.let { (done, total) ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Importing ${done.coerceAtMost(total)} of $total…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (summary != null) {
                TextButton(onClick = onComplete) { Text("Done") }
            } else {
                LoadingTextButton(
                    onClick = {
                        if (needsSourceAccount && sourceAccountId == null) return@LoadingTextButton
                        isImporting = true
                        scope.launch {
                            val result =
                                bulkApplyCsv(
                                    imports = unimported,
                                    sourceAccountOverride = sourceAccountId,
                                    strategies = strategies,
                                    currencies = currencies,
                                    csvAccountMappingRepository = csvAccountMappingRepository,
                                    accountRepository = accountRepository,
                                    csvImportRepository = csvImportRepository,
                                    attributeTypeRepository = attributeTypeRepository,
                                    maintenance = maintenance,
                                    entitySource = entitySource,
                                    importEngine = importEngine,
                                    onProgress = { done, total -> progress = done to total },
                                )
                            summary = result.toSummary()
                            isImporting = false
                        }
                    },
                    enabled = !isImporting && (!needsSourceAccount || sourceAccountId != null),
                    loading = isImporting,
                    label = "Import ${unimported.size} files",
                )
            }
        },
        dismissButton = {
            if (summary == null) {
                TextButton(onClick = onDismiss, enabled = !isImporting) { Text("Cancel") }
            }
        },
    )
}

private fun CsvBulkResult.toSummary(): String =
    buildString {
        append("Imported $filesImported file${if (filesImported == 1) "" else "s"}")
        append(" · $transfersCreated new")
        if (duplicatesSkipped > 0) append(" · $duplicatesSkipped duplicates skipped")
        if (filesSkippedNoStrategy > 0) append(" · $filesSkippedNoStrategy skipped (no strategy)")
        if (filesFailed > 0) append(" · $filesFailed failed")
    }
