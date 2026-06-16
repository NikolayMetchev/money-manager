@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.qif.QifImportRecord
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
import com.moneymanager.ui.components.qif.QifRecordList
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.qif.QifApplyStrategyDialog
import com.moneymanager.ui.util.displayDateTime
import kotlinx.coroutines.launch

@Composable
fun QifImportDetailScreen(
    importId: QifImportId,
    qifImportRepository: QifImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    attributeTypeRepository: AttributeTypeRepository,
    settingsRepository: SettingsRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    importEngine: ImportEngine,
    onBack: () -> Unit,
    onCreateStrategy: (QifImportId) -> Unit,
    onDeleted: () -> Unit,
    onTransferClick: (TransferId, Boolean) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val import by qifImportRepository.getImport(importId).collectAsStateWithSchemaErrorHandling(initial = null)

    var records by remember { mutableStateOf<List<QifImportRecord>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showApplyDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(importId, refreshTrigger) {
        val count = qifImportRepository.countRecords(importId)
        records = qifImportRepository.getImportRecords(importId, limit = count.coerceAtLeast(1), offset = 0)
    }

    val current = import

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) {
            Text("< Back")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current?.originalFileName ?: "QIF Import",
                    style = MaterialTheme.typography.headlineSmall,
                )
                current?.let { qifImport ->
                    Text(
                        text =
                            buildString {
                                append("${qifImport.recordCount} records · ${qifImport.accountType}")
                                if (qifImport.unsupportedCount > 0) append(" · ${qifImport.unsupportedCount} unsupported")
                                append(" · added ${qifImport.importTimestamp.displayDateTime()}")
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDeleteDialog = true }) {
                    Text("Delete")
                }
                OutlinedButton(onClick = { onCreateStrategy(importId) }) {
                    Text("Create Strategy")
                }
                // Enabled only while there are supported records that still need importing — once a
                // strategy has been applied (every record imported/duplicate), there's nothing to re-do.
                val hasPendingRecords =
                    records.any { it.supported && (it.importStatus == null || it.importStatus == ImportStatus.ERROR) }
                Button(
                    onClick = { showApplyDialog = true },
                    enabled = hasPendingRecords,
                ) {
                    Text("Apply Strategy")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        QifRecordList(
            records = records,
            onTransferClick = onTransferClick,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (showApplyDialog && current != null) {
        QifApplyStrategyDialog(
            qifImport = current,
            records = records,
            csvImportStrategyRepository = csvImportStrategyRepository,
            csvAccountMappingRepository = csvAccountMappingRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
            qifImportRepository = qifImportRepository,
            attributeTypeRepository = attributeTypeRepository,
            settingsRepository = settingsRepository,
            maintenance = maintenance,
            entitySource = entitySource,
            importEngine = importEngine,
            onDismiss = { showApplyDialog = false },
            onImportComplete = {
                showApplyDialog = false
                refreshTrigger++
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete QIF Import") },
            text = { Text("Delete this import? Transactions already created from it are not removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        qifImportRepository.deleteImport(importId)
                        onDeleted()
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
