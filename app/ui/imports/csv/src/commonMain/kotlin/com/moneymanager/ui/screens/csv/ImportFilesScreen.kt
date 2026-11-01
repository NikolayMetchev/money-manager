package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberMultipleBinaryFilePicker
import com.moneymanager.csvimporter.importFileChecksum
import com.moneymanager.csvimporter.stageImportFile
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.importFileKind
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importdirectory.canDownloadAll
import com.moneymanager.importdirectory.startDownloadAll
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.setCsvImportIgnored
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.MultiSelectFilterDropdown
import com.moneymanager.ui.components.imports.ImportSectionHeader
import com.moneymanager.ui.components.imports.ImportStatusMessage
import com.moneymanager.ui.components.imports.ImportsScreenHeader
import com.moneymanager.ui.components.imports.ReimportAllDialog
import com.moneymanager.ui.components.imports.ReimportPickerRepositories
import com.moneymanager.ui.components.imports.StagedImportCard
import com.moneymanager.ui.components.imports.StagedImportFilter
import com.moneymanager.ui.components.imports.StagedImportsData
import com.moneymanager.ui.components.imports.StagedImportsTabs
import com.moneymanager.ui.components.imports.importPickedFiles
import com.moneymanager.ui.components.imports.rememberStagedImportsData
import com.moneymanager.ui.components.imports.sortedByTransactionDates
import com.moneymanager.ui.components.imports.stagedImportDirectoryOptions
import com.moneymanager.ui.components.imports.stagedImportKinds
import com.moneymanager.ui.components.imports.stagedImportPath
import com.moneymanager.ui.components.imports.stagedImportStrategyOptions
import com.moneymanager.ui.tasks.LocalTaskManager
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Every staged file, whatever its format and wherever it came from: the import directories panel ([directories],
 * null leaves it out, for a front end with no import folders), "+ Import files", a search and filters, then the
 * Unimported / Imported / Ignored tabs. [directories] gets what a folder's "Show files" does (filter to that folder)
 * and what to call when it starts a download (keep the panel open).
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun ImportFilesScreen(
    importDirectoryService: ImportDirectoryService,
    csvImportRepository: CsvImportReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    importTimelineRepository: ImportTimelineReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onImportClick: (CsvImportId) -> Unit,
    onStrategiesClick: () -> Unit = {},
    directories: (@Composable (onShowFiles: (ImportDirectory) -> Unit, onRunStarted: () -> Unit) -> Unit)? = null,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val stagedImports by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        csvImportRepository.getAllImports()
    }
    val dateRanges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importTimelineRepository.getCsvImportDateRanges().map { ranges -> ranges.associateBy { it.fileId } }
    }
    val imports =
        remember(stagedImports, dateRanges) {
            sortedByTransactionDates(stagedImports) { dateRanges[it.id.id.toString()] }
        }
    val data =
        rememberStagedImportsData(
            imports = imports,
            importDirectoryRepository = importDirectoryRepository,
            csvImportStrategyRepository = csvImportStrategyRepository,
            accountRepository = accountRepository,
            importDirectoryIds = { csvImportDirectories() },
            sourceAccounts = { csvImportSourceAccounts() },
            matchStrategies = importDirectoryService::matchCsvStrategies,
        )

    var isImporting by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importMessageIsError by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(StagedImportFilter()) }
    val taskManager = LocalTaskManager.current
    val tasks by taskManager.tasks.collectAsState()
    // Null until the person opens or closes it or starts a download: then open only while there are no files yet.
    // A download latches the current state, or the files it stages would fold an open panel (its folders, progress
    // and outcome) away mid-run.
    var directoriesExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }

    val filePicker =
        rememberMultipleBinaryFilePicker(mimeTypes = IMPORT_FILE_MIME_TYPES) { results ->
            if (results.isNotEmpty()) {
                isImporting = true
                importMessage = null
                scope.launch {
                    val outcome =
                        importPickedFiles(results, fileName = { it.fileName }) { result ->
                            val kind =
                                requireNotNull(importFileKind(result.fileName)) { "not a CSV, QIF, Excel or PDF file" }
                            val checksum = importFileChecksum(kind, result.bytes)
                            if (csvImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                                return@importPickedFiles false
                            }
                            importEngine.stageImportFile(
                                kind,
                                result.fileName,
                                result.bytes,
                                checksum,
                                result.lastModified ?: Clock.System.now(),
                                csvImportRepository,
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
            title = "Files",
            importButtonLabel = "+ Import files",
            isImporting = isImporting,
            onImportClick = { filePicker.launch() },
            onStrategiesClick = onStrategiesClick,
        )

        ImportStatusMessage(message = importMessage, isError = importMessageIsError)

        Spacer(modifier = Modifier.height(16.dp))

        run {
            // The list to run "Import all" against (a folder's or strategy group's files, or every unimported
            // file when launched from the top-level button); null hides the dialog.
            var importAllScope by remember { mutableStateOf<List<CsvImport>?>(null) }
            // Same idea for "Re-import all".
            var reimportAllScope by remember { mutableStateOf<List<CsvImport>?>(null) }
            // The imported file whose Unimport was clicked; null hides the dialog.
            var unimportTarget by remember { mutableStateOf<CsvImport?>(null) }
            // Same idea as reimportAllScope, for "Unimport all".
            var unimportAllScope by remember { mutableStateOf<List<CsvImport>?>(null) }

            importAllScope?.let { scopedUnimported ->
                CsvImportAllDialog(
                    unimported = scopedUnimported,
                    importDirectoryService = importDirectoryService,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    onDismiss = { importAllScope = null },
                    onComplete = { importAllScope = null },
                    fileLabel = { stagedImportPath(it, data.importDirectories) },
                )
            }

            reimportAllScope?.let { scopedImported ->
                ReimportAllDialog(
                    files = scopedImported,
                    reimports = importDirectoryService.reimports,
                    pickers = ReimportPickerRepositories(accountRepository, categoryRepository, currencyRepository, personRepository),
                    onDismiss = { reimportAllScope = null },
                    onComplete = { reimportAllScope = null },
                    fileLabel = { stagedImportPath(it, data.importDirectories) },
                )
            }

            unimportTarget?.let { target ->
                UnimportDialog(
                    csvImport = target,
                    csvImportRepository = csvImportRepository,
                    transferSourceRepository = transferSourceRepository,
                    accountRepository = accountRepository,
                    transactionRepository = transactionRepository,
                    transferRelationshipRepository = transferRelationshipRepository,
                    tradeRepository = tradeRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onDismiss = { unimportTarget = null },
                    onComplete = { result ->
                        unimportTarget = null
                        importMessageIsError = false
                        importMessage = result.summary(target.originalFileName)
                    },
                )
            }

            unimportAllScope?.let { scoped ->
                CsvUnimportAllDialog(
                    imports = scoped,
                    csvImportRepository = csvImportRepository,
                    transferSourceRepository = transferSourceRepository,
                    accountRepository = accountRepository,
                    transactionRepository = transactionRepository,
                    transferRelationshipRepository = transferRelationshipRepository,
                    tradeRepository = tradeRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onDismiss = { unimportAllScope = null },
                    onComplete = { result ->
                        unimportAllScope = null
                        importMessageIsError = false
                        importMessage = result.summary("${scoped.size} file(s)")
                    },
                )
            }

            StagedImportsTabs(
                imports = imports,
                data = data,
                onImportAll = { importAllScope = it },
                onReimportAll = { reimportAllScope = it },
                onSetIgnored = { files, ignore ->
                    scope.launch { files.forEach { importEngine.setCsvImportIgnored(it.id, ignore) } }
                },
                onUnimportAll = { unimportAllScope = it },
                filter = filter,
                header = {
                    if (directories != null) {
                        val expanded = directoriesExpanded ?: imports.isEmpty()
                        item(key = "directories-header") {
                            // In the header, so it's there folded too: the global task panel shows its progress.
                            ImportSectionHeader(
                                title = "Directories",
                                count = data.directories.count { it.parentId == null },
                                expanded = expanded,
                                onToggleExpanded = { directoriesExpanded = !expanded },
                                actionLabel = "Download all",
                                actionEnabled = importDirectoryService.canDownloadAll(data.directories, tasks),
                                onAction = {
                                    directoriesExpanded = expanded
                                    importDirectoryService.startDownloadAll(taskManager)
                                },
                            )
                        }
                        if (expanded) {
                            item(key = "directories") {
                                directories(
                                    { directory ->
                                        filter = StagedImportFilter(directories = setOf(directory.id))
                                        directoriesExpanded = false
                                    },
                                    { directoriesExpanded = true },
                                )
                            }
                        }
                    }
                    if (imports.isEmpty()) {
                        item(key = "no-files") {
                            Text(
                                "No files yet. Click '+ Import files' to add CSV, QIF, Excel or PDF files" +
                                    if (directories != null) ", or download a directory." else ".",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        item(key = "filters") {
                            StagedImportFilters(imports, data, filter, onFilterChange = { filter = it })
                        }
                    }
                },
            ) { import, placement ->
                StagedImportCard(
                    import = import,
                    placement = placement,
                    metadataText = "${import.rowCount} rows, ${import.columnCount} columns",
                    dateRange = dateRanges[import.id.id.toString()],
                    sourceAccountName = data.sourceAccountName(import),
                    sourceDirectories = data.importDirectories[import.id].orEmpty(),
                    onClick = { onImportClick(import.id) },
                    onSetIgnored = { ignore -> scope.launch { importEngine.setCsvImportIgnored(import.id, ignore) } },
                    lastUnimportedAt = import.lastUnimportedAt,
                    onUnimport = { unimportTarget = import },
                )
            }
        }
    }
}

// The JVM picker filters by the extensions these map to; Android and the browser pass them on as-is.
private val IMPORT_FILE_MIME_TYPES =
    listOf(
        "text/csv",
        "text/comma-separated-values",
        "text/plain",
        "application/qif",
        "application/x-qif",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/pdf",
        "application/octet-stream",
    )

/** The search field and the file type, strategy and directory filters; a filter with one choice is left out. */
@Composable
private fun StagedImportFilters(
    imports: List<CsvImport>,
    data: StagedImportsData<CsvImportId>,
    filter: StagedImportFilter,
    onFilterChange: (StagedImportFilter) -> Unit,
) {
    val kinds = remember(imports) { stagedImportKinds(imports) }
    val strategies = remember(imports, data.matchedStrategies) { stagedImportStrategyOptions(imports, data.matchedStrategies) }
    val folders =
        remember(imports, data.importDirectories, data.directories) {
            stagedImportDirectoryOptions(imports, data.importDirectories, data.directories)
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter.text,
            onValueChange = { onFilterChange(filter.copy(text = it)) },
            label = { Text("Search files") },
            placeholder = { Text("Type to search...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (kinds.size > 1 || filter.kinds.isNotEmpty()) {
            MultiSelectFilterDropdown(
                items = kinds,
                selectedKeys = filter.kinds,
                itemKey = { it },
                itemLabel = { it.label },
                itemNoun = "file type",
                onSelectionChange = { onFilterChange(filter.copy(kinds = it)) },
            )
        }
        if (strategies.size > 1 || filter.strategies.isNotEmpty()) {
            MultiSelectFilterDropdown(
                items = strategies,
                selectedKeys = filter.strategies,
                itemKey = { it.id },
                itemLabel = { it.label },
                itemNoun = "strategy",
                itemNounPlural = "strategies",
                onSelectionChange = { onFilterChange(filter.copy(strategies = it)) },
            )
        }
        if (folders.size > 1 || filter.directories.isNotEmpty()) {
            MultiSelectFilterDropdown(
                items = folders,
                selectedKeys = filter.directories,
                itemKey = { it.first },
                itemLabel = { it.second },
                itemNoun = "directory",
                itemNounPlural = "directories",
                onSelectionChange = { onFilterChange(filter.copy(directories = it)) },
            )
        }
    }
}
