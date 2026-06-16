package com.moneymanager.ui.screens.qif

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
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.QifImportRepository
import com.moneymanager.domain.repository.SettingsRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies the matching QIF strategy to every unimported file in one go, using a single source account
 * and currency chosen here. The source account defaults to (and is remembered as) the last account
 * used for QIF imports. Payee accounts auto-create with their detected names.
 */
@Composable
// Shares the single-file QIF apply flow's source-account/currency selection by design.
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun QifImportAllDialog(
    unimported: List<QifImport>,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    qifImportRepository: QifImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    settingsRepository: SettingsRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository.getAllStrategies().collectAsStateWithSchemaErrorHandling(emptyList())
    val currencies by currencyRepository.getAllCurrencies().collectAsStateWithSchemaErrorHandling(emptyList())
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(emptyList())

    var sourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accounts) {
        if (sourceAccountId == null) {
            val last = settingsRepository.getLastQifAccountId().first()
            if (last != null && accounts.any { it.id == last }) sourceAccountId = last
        }
    }
    LaunchedEffect(currencies) {
        if (selectedCurrencyId == null && currencies.isNotEmpty()) {
            val defaultId = settingsRepository.getDefaultCurrencyId().first()
            selectedCurrencyId = defaultId?.takeIf { id -> currencies.any { it.id == id } } ?: currencies.firstOrNull()?.id
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(if (summary != null) "Import complete" else "Import all unimported files") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentSummary = summary
                if (currentSummary != null) {
                    Text(currentSummary, style = MaterialTheme.typography.bodyMedium)
                } else {
                    AccountPicker(
                        selectedAccountId = sourceAccountId,
                        onAccountSelected = { sourceAccountId = it },
                        label = "Source account for all files",
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        personAccountOwnershipRepository = personAccountOwnershipRepository,
                        enabled = !isImporting,
                        isError = sourceAccountId == null,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    CurrencyPicker(
                        selectedCurrencyId = selectedCurrencyId,
                        onCurrencySelected = { selectedCurrencyId = it },
                        label = "Currency",
                        currencyRepository = currencyRepository,
                        enabled = !isImporting,
                        isError = selectedCurrencyId == null,
                    )
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
                        val source = sourceAccountId ?: return@LoadingTextButton
                        val currency = selectedCurrencyId ?: return@LoadingTextButton
                        isImporting = true
                        scope.launch {
                            try {
                                val result =
                                    bulkApplyQif(
                                        imports = unimported,
                                        sourceAccountId = source,
                                        currencyId = currency,
                                        strategies = strategies,
                                        currencies = currencies,
                                        csvAccountMappingRepository = csvAccountMappingRepository,
                                        accountRepository = accountRepository,
                                        qifImportRepository = qifImportRepository,
                                        attributeTypeRepository = attributeTypeRepository,
                                        maintenance = maintenance,
                                        importEngine = importEngine,
                                        onProgress = { done, total -> progress = done to total },
                                    )
                                settingsRepository.setLastQifAccountId(source)
                                summary = result.toSummary()
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    enabled = !isImporting && sourceAccountId != null && selectedCurrencyId != null,
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
