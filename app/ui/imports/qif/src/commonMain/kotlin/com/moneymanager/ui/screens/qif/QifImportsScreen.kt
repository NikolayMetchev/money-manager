package com.moneymanager.ui.screens.qif

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberMultipleFilePicker
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createQifImport
import com.moneymanager.importengineapi.setQifImportIgnored
import com.moneymanager.qif.QifParser
import com.moneymanager.ui.components.imports.ImportFileCard
import com.moneymanager.ui.components.imports.ImportStatusMessage
import com.moneymanager.ui.components.imports.ImportTab
import com.moneymanager.ui.components.imports.ImportTabsRow
import com.moneymanager.ui.components.imports.ImportsEmptyMessage
import com.moneymanager.ui.components.imports.ImportsScreenHeader
import com.moneymanager.ui.components.imports.emptyImportTabMessage
import com.moneymanager.ui.components.imports.importPickedFiles
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.sha256Hex
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
@Suppress("LongParameterList")
fun QifImportsScreen(
    qifImportRepository: QifImportReadRepository,
    importTimelineRepository: ImportTimelineReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    settingsRepository: SettingsReadRepository,
    transactionRepository: TransactionReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onImportClick: (QifImportId) -> Unit,
    onStrategiesClick: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        qifImportRepository.getAllImports()
    }
    val dateRanges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importTimelineRepository.getQifImportDateRanges().map { ranges -> ranges.associateBy { it.fileId } }
    }
    var isImporting by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importMessageIsError by remember { mutableStateOf(false) }

    val filePicker =
        rememberMultipleFilePicker(
            // .qif has no standard MIME. Desktop filters by extension (the qif types map to .qif only,
            // so the chooser shows just .qif files); Android often reports .qif as octet-stream.
            mimeTypes = listOf("application/qif", "application/x-qif", "application/octet-stream"),
        ) { results ->
            if (results.isNotEmpty()) {
                isImporting = true
                importMessage = null
                scope.launch {
                    val outcome =
                        importPickedFiles(results, fileName = { it.fileName }) { result ->
                            val checksum = sha256Hex(result.content)
                            if (qifImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                                return@importPickedFiles false
                            }
                            val parseResult = QifParser().parse(result.content)
                            importEngine.createQifImport(
                                fileName = result.fileName,
                                records = parseResult.toImportRecords(),
                                accountType = parseResult.dominantAccountType(),
                                fileChecksum = checksum,
                                fileLastModified = result.lastModified ?: Clock.System.now(),
                            )
                            true
                        }
                    isImporting = false
                    importMessageIsError = outcome.isError
                    importMessage = outcome.message
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        ImportsScreenHeader(
            title = "QIF Imports",
            importButtonLabel = "+ Import QIF",
            isImporting = isImporting,
            onImportClick = { filePicker.launch() },
            onStrategiesClick = onStrategiesClick,
        )

        ImportStatusMessage(message = importMessage, isError = importMessageIsError)

        Spacer(modifier = Modifier.height(16.dp))

        if (imports.isEmpty() && !isImporting) {
            ImportsEmptyMessage("No QIF files added yet. Click '+ Import QIF' to add one or more.")
        } else {
            // Split files into those still needing a strategy applied vs. already imported, so a large
            // set of files is easy to work through. The Unimported tab is the default/actionable one.
            val unimported = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt == null } }
            val importedList = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt != null } }
            val ignoredList = remember(imports) { imports.filter { it.ignored } }
            var selectedTab by remember { mutableStateOf(ImportTab.UNIMPORTED) }

            ImportTabsRow(
                selectedTab = selectedTab,
                unimportedCount = unimported.size,
                importedCount = importedList.size,
                ignoredCount = ignoredList.size,
                onTabSelected = { selectedTab = it },
            )

            Spacer(modifier = Modifier.height(12.dp))

            var showImportAll by remember { mutableStateOf(false) }
            if (selectedTab == ImportTab.UNIMPORTED && unimported.isNotEmpty()) {
                Button(
                    onClick = { showImportAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import all (${unimported.size})")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            var showReimportAll by remember { mutableStateOf(false) }
            if (selectedTab == ImportTab.IMPORTED && importedList.isNotEmpty()) {
                Button(
                    onClick = { showReimportAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-import all (${importedList.size})")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (showImportAll) {
                QifImportAllDialog(
                    unimported = unimported,
                    importDirectoryRepository = importDirectoryRepository,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    qifImportRepository = qifImportRepository,
                    settingsRepository = settingsRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onDismiss = { showImportAll = false },
                    onComplete = { showImportAll = false },
                )
            }

            if (showReimportAll) {
                QifReimportAllDialog(
                    imported = importedList,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    qifImportRepository = qifImportRepository,
                    transactionRepository = transactionRepository,
                    transferSourceRepository = transferSourceRepository,
                    settingsRepository = settingsRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onDismiss = { showReimportAll = false },
                    onComplete = { showReimportAll = false },
                )
            }

            val shown =
                when (selectedTab) {
                    ImportTab.UNIMPORTED -> unimported
                    ImportTab.IMPORTED -> importedList
                    else -> ignoredList
                }
            if (shown.isEmpty()) {
                ImportsEmptyMessage(emptyImportTabMessage(selectedTab))
            } else {
                val ignoredTab = selectedTab == ImportTab.IGNORED
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id.toString() }) { import ->
                        ImportFileCard(
                            fileName = import.originalFileName,
                            metadataText =
                                buildString {
                                    append("${import.recordCount} records")
                                    append(" · ${import.accountType}")
                                    if (import.unsupportedCount > 0) {
                                        append(" · ${import.unsupportedCount} unsupported")
                                    }
                                },
                            addedAt = import.importTimestamp,
                            errorCount = import.errorCount,
                            lastAppliedAt = import.lastAppliedAt,
                            applicationCount = import.applicationCount,
                            lastAppliedStrategyName = import.lastAppliedStrategyName,
                            dateRange = dateRanges[import.id.id.toString()],
                            ignored = ignoredTab,
                            onClick = { onImportClick(import.id) },
                            onSetIgnored = { ignore ->
                                scope.launch { importEngine.setQifImportIgnored(import.id, ignore) }
                            },
                        )
                    }
                }
            }
        }
    }
}
