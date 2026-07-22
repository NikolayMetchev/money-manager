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
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.setLastQifAccount
import com.moneymanager.qifimporter.bulkApplyQif
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.csv.BulkImportProgressIndicator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies the matching QIF strategy to every unimported file in one go, using a single source account
 * chosen here. The currency is taken from each file's auto-detected strategy (so there is no currency
 * prompt). The source account defaults to (and is remembered as) the last account used for QIF imports.
 * Payee accounts auto-create with their detected names.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "DuplicatedCode")
fun QifImportAllDialog(
    unimported: List<QifImport>,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    qifImportRepository: QifImportReadRepository,
    settingsRepository: SettingsReadRepository,
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
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BulkImportProgress?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    // The account each file's own import directory (or that directory's parent) resolves to, when set.
    // Takes priority over the shared picker below, so two folders of identically-formatted files each
    // land on their own account without user disambiguation.
    var directoryAccounts by remember { mutableStateOf<Map<QifImportId, AccountId>>(emptyMap()) }
    LaunchedEffect(Unit) {
        directoryAccounts = importDirectoryRepository.qifImportSourceAccounts()
    }
    val needsSourceAccount = unimported.any { it.id !in directoryAccounts }

    LaunchedEffect(accounts) {
        if (sourceAccountId == null) {
            val last = settingsRepository.getLastQifAccountId().first()
            if (last != null && accounts.any { it.id == last }) sourceAccountId = last
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
                    if (needsSourceAccount) {
                        AccountPicker(
                            selectedAccountId = sourceAccountId,
                            onAccountSelected = { sourceAccountId = it },
                            label = "Source account (for files whose folder has none set)",
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            enabled = !isImporting,
                            isError = sourceAccountId == null,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    progress?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        BulkImportProgressIndicator(it)
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
                            try {
                                val result =
                                    bulkApplyQif(
                                        imports = unimported,
                                        sourceAccountId = sourceAccountId,
                                        strategies = strategies,
                                        currencies = currencies,
                                        accountMappingRepository = accountMappingRepository,
                                        accountRepository = accountRepository,
                                        qifImportRepository = qifImportRepository,
                                        maintenance = maintenance,
                                        importEngine = importEngine,
                                        onProgress = { progress = it },
                                        directoryAccounts = directoryAccounts,
                                    )
                                sourceAccountId?.let { importEngine.setLastQifAccount(it) }
                                summary = result.toSummary()
                            } finally {
                                isImporting = false
                            }
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
