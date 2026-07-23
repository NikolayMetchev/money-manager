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
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.strategy.CsvImportParseResult
import com.moneymanager.domain.strategy.CsvReferenceType
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.ui.components.ReferenceResolutionRow
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialog for importing a CSV strategy from a JSON file.
 * Shows unresolved references and allows the user to map them to existing entities or create new ones.
 * Persistence goes through [StrategyLibrary.applyIncoming] — the same path as a catalog/library
 * install — so a strategy with the same name is updated IN PLACE (keeping its id, so staged imports
 * referencing it keep working) and its per-strategy account mappings are replaced by the file's.
 */
@Composable
fun ImportStrategyDialog(
    parseResult: CsvImportParseResult,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    strategyLibrary: StrategyLibrary,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    onDismiss: () -> Unit,
    onImportSuccess: () -> Unit,
) {
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

    // A same-named strategy is overwritten in place rather than blocking the import.
    val existingStrategies by csvImportStrategyRepository
        .getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val existingWithName = existingStrategies.firstOrNull { it.name == strategyName }

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
                    isError = strategyName.isBlank(),
                    supportingText = {
                        when {
                            strategyName.isBlank() -> Text("Name is required")
                            existingWithName != null ->
                                Text(
                                    "A strategy with this name already exists — it will be updated in " +
                                        "place and its per-strategy account mappings replaced.",
                                    color = MaterialTheme.colorScheme.tertiary,
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
                        try {
                            // The library path creates or updates-in-place by name, applying the
                            // strategy and its per-strategy mappings as one engine batch. The key
                            // carries the (possibly renamed) target name; CSV and QIF strategy files
                            // share the same artifact format and apply path.
                            strategyLibrary.applyIncoming(
                                key = StrategyKey(StrategyKind.CSV, strategyName),
                                json = CsvStrategyExportCodec.encode(parseResult.export.copy(name = strategyName)),
                                resolutions = resolutions,
                            )
                            onImportSuccess()
                            onDismiss()
                        } catch (expected: Exception) {
                            errorMessage = "Failed to import: ${expected.message}"
                            isImporting = false
                        }
                    }
                },
                enabled = allResolved && strategyName.isNotBlank() && !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (existingWithName != null) "Overwrite" else "Import")
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
