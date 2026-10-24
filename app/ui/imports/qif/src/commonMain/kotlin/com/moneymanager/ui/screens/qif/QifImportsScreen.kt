package com.moneymanager.ui.screens.qif

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberMultipleFilePicker
import com.moneymanager.csvimporter.sha256Hex
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createQifImport
import com.moneymanager.importengineapi.setQifImportIgnored
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.qif.QifParser
import com.moneymanager.qifimporter.StagedFile
import com.moneymanager.ui.components.imports.ImportStatusMessage
import com.moneymanager.ui.components.imports.ImportsEmptyMessage
import com.moneymanager.ui.components.imports.ImportsScreenHeader
import com.moneymanager.ui.components.imports.ReimportAllDialog
import com.moneymanager.ui.components.imports.ReimportPickerRepositories
import com.moneymanager.ui.components.imports.StagedImportCard
import com.moneymanager.ui.components.imports.StagedImportsTabs
import com.moneymanager.ui.components.imports.importPickedFiles
import com.moneymanager.ui.components.imports.rememberStagedImportsData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
@Suppress("LongParameterList")
fun QifImportsScreen(
    importDirectoryService: ImportDirectoryService,
    qifImportRepository: QifImportReadRepository,
    importTimelineRepository: ImportTimelineReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
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
    val data =
        rememberStagedImportsData(
            imports = imports,
            importDirectoryRepository = importDirectoryRepository,
            csvImportStrategyRepository = csvImportStrategyRepository,
            accountRepository = accountRepository,
            importDirectoryIds = { qifImportDirectories() },
            sourceAccounts = { qifImportSourceAccounts() },
            matchStrategies = importDirectoryService::matchQifStrategies,
        )

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
            // The list to run "Import all" against (a folder's or strategy group's files, or every unimported
            // file when launched from the top-level button); null hides the dialog.
            var importAllScope by remember { mutableStateOf<List<QifImport>?>(null) }
            // Same idea for "Re-import all".
            var reimportAllScope by remember { mutableStateOf<List<QifImport>?>(null) }

            importAllScope?.let { scoped ->
                QifImportAllDialog(
                    unimported = scoped,
                    importDirectoryService = importDirectoryService,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    onDismiss = { importAllScope = null },
                    onComplete = { importAllScope = null },
                )
            }

            reimportAllScope?.let { scoped ->
                ReimportAllDialog(
                    files = scoped.map(StagedFile::Qif),
                    reimports = importDirectoryService.reimports,
                    pickers = ReimportPickerRepositories(accountRepository, categoryRepository, currencyRepository, personRepository),
                    onDismiss = { reimportAllScope = null },
                    onComplete = { reimportAllScope = null },
                )
            }

            StagedImportsTabs(
                imports = imports,
                data = data,
                onImportAll = { importAllScope = it },
                onReimportAll = { reimportAllScope = it },
                onSetIgnored = { files, ignore ->
                    scope.launch { files.forEach { importEngine.setQifImportIgnored(it.id, ignore) } }
                },
            ) { import, placement ->
                StagedImportCard(
                    import = import,
                    placement = placement,
                    metadataText =
                        buildString {
                            append("${import.recordCount} records")
                            append(" · ${import.accountType}")
                            if (import.unsupportedCount > 0) {
                                append(" · ${import.unsupportedCount} unsupported")
                            }
                        },
                    dateRange = dateRanges[import.id.id.toString()],
                    sourceAccountName = data.sourceAccountName(import),
                    sourceDirectories = data.importDirectories[import.id].orEmpty(),
                    onClick = { onImportClick(import.id) },
                    onSetIgnored = { ignore -> scope.launch { importEngine.setQifImportIgnored(import.id, ignore) } },
                )
            }
        }
    }
}
