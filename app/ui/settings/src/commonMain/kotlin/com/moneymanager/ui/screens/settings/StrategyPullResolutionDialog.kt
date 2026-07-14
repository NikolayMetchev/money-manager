package com.moneymanager.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.ui.components.ReferenceResolutionRow
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling

/**
 * Collects resolutions for the references that the selected remote strategies need before they can be
 * imported. References are de-duplicated across artifacts (the same account name resolves once and is
 * applied to every artifact that uses it). Every reference defaults to being created on import; the user
 * can override any of them to map to an existing entity.
 */
@Composable
fun StrategyPullResolutionDialog(
    unresolvedByKey: Map<StrategyKey, List<CsvUnresolvedReference>>,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    onConfirm: (Map<StrategyKey, Map<CsvUnresolvedReference, CsvResolution>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val categories by categoryRepository.getAllCategories().collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(initial = emptyList())

    val allReferences = remember(unresolvedByKey) { unresolvedByKey.values.flatten().distinct() }
    var resolutions by remember(allReferences) {
        mutableStateOf<Map<CsvUnresolvedReference, CsvResolution>>(
            allReferences.associateWith { CsvResolution.CreateNew(it.name) },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve references") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "These strategies reference accounts, categories or currencies that don't exist here yet. " +
                        "Each will be created on import unless you map it to an existing one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                allReferences.forEach { reference ->
                    ReferenceResolutionRow(
                        reference = reference,
                        resolution = resolutions[reference],
                        onResolutionChanged = { resolution -> resolutions = resolutions + (reference to resolution) },
                        accounts = accounts,
                        categories = categories,
                        currencies = currencies,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        enabled = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val byKey = unresolvedByKey.mapValues { (_, refs) -> refs.associateWith { resolutions.getValue(it) } }
                    onConfirm(byKey)
                },
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
