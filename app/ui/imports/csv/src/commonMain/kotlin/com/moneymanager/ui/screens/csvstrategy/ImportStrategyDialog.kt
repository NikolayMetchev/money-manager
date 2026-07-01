@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.csvstrategy

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.CsvImportParseResult
import com.moneymanager.domain.CsvReferenceType
import com.moneymanager.domain.CsvResolution
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.CsvUnresolvedReference
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createCsvStrategy
import com.moneymanager.importengineapi.deleteCsvStrategy
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.components.ReferenceResolutionRow
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialog for importing a CSV strategy from a JSON file.
 * Shows unresolved references and allows the user to map them to existing entities or create new ones.
 */
@Composable
fun ImportStrategyDialog(
    parseResult: CsvImportParseResult,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    csvStrategyImportExport: CsvStrategyImportExport,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    onDismiss: () -> Unit,
    onImportSuccess: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val accounts by accountRepository
        .getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val categories by categoryRepository
        .getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var resolutions by remember {
        mutableStateOf<Map<CsvUnresolvedReference, CsvResolution>>(emptyMap())
    }
    var strategyName by remember { mutableStateOf(parseResult.strategyName) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    // Check if all unresolved references have been resolved
    val allResolved = parseResult.unresolvedReferences.all { it in resolutions.keys }
    val unresolvedAccountReferences =
        parseResult.unresolvedReferences.filter { reference ->
            reference.type == CsvReferenceType.ACCOUNT && reference !in resolutions.keys
        }

    // Check for name conflict
    val existingStrategies by csvImportStrategyRepository
        .getAllStrategies()
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
                    if (unresolvedAccountReferences.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                resolutions =
                                    resolutions +
                                    unresolvedAccountReferences.associateWith { reference ->
                                        CsvResolution.CreateNew(reference.name)
                                    }
                            },
                            enabled = !isImporting,
                        ) {
                            Text("Create New for All Unresolved Accounts")
                        }
                    }
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
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
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
                        var createdStrategyId: CsvImportStrategyId? = null
                        try {
                            // Update the export with the new name if changed
                            val exportWithNewName = parseResult.export.copy(name = strategyName)

                            // Create the strategy
                            val result =
                                csvStrategyImportExport.createStrategyFromExport(
                                    export = exportWithNewName,
                                    resolutions = resolutions,
                                )

                            val persistedStrategyId = importEngine.createCsvStrategy(result.strategy)
                            createdStrategyId = persistedStrategyId
                            // Persist the strategy's embedded per-strategy account mappings (they
                            // FK-reference the strategy row, so create them after the strategy).
                            if (result.accountMappings.isNotEmpty()) {
                                importEngine.createAccountMappings(result.accountMappings)
                            }
                            onImportSuccess()
                            onDismiss()
                        } catch (expected: Exception) {
                            createdStrategyId?.let { strategyId ->
                                runCatching {
                                    importEngine.deleteCsvStrategy(strategyId)
                                }
                            }
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
