package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.BinaryFilePickerResult
import com.moneymanager.compose.filepicker.rememberBinaryFilePicker
import com.moneymanager.compose.filepicker.rememberMultipleFilePicker
import com.moneymanager.csv.CsvParseOptions
import com.moneymanager.csv.CsvParser
import com.moneymanager.csvimporter.sha256Hex
import com.moneymanager.csvimporter.stagePdfImport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csv.CsvImport
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
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.importengineapi.createXlsxImport
import com.moneymanager.importengineapi.setCsvImportIgnored
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
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
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

@Suppress("LongParameterList", "LongMethod")
@Composable
fun CsvImportsScreen(
    importDirectoryService: ImportDirectoryService,
    csvImportRepository: CsvImportReadRepository,
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
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        csvImportRepository.getAllImports()
    }
    val dateRanges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importTimelineRepository.getCsvImportDateRanges().map { ranges -> ranges.associateBy { it.fileId } }
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

    val filePicker =
        rememberMultipleFilePicker(
            mimeTypes = listOf("text/csv", "text/plain", "text/comma-separated-values"),
        ) { results ->
            if (results.isNotEmpty()) {
                isImporting = true
                importMessage = null
                scope.launch {
                    val outcome =
                        importPickedFiles(results, fileName = { it.fileName }) { result ->
                            val checksum = sha256Hex(result.content)
                            if (csvImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                                return@importPickedFiles false
                            }
                            val parser = CsvParser()
                            val delimiter = parser.detectDelimiter(result.content)
                            val parseResult =
                                parser.parse(
                                    result.content,
                                    CsvParseOptions(delimiter = delimiter),
                                )
                            importEngine.createCsvImport(
                                fileName = result.fileName,
                                headers = parseResult.headers,
                                rows = parseResult.rows,
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

    // Excel and PDF files are staged from their bytes; one picked file at a time, skipped if already imported.
    fun stageBinaryFile(
        result: BinaryFilePickerResult,
        stage: suspend (checksum: String, lastModified: Instant) -> Unit,
    ) {
        isImporting = true
        importMessage = null
        scope.launch {
            importMessageIsError = false
            importMessage =
                try {
                    val checksum = sha256Hex(result.bytes)
                    if (csvImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                        "Skipped ${result.fileName}: already imported"
                    } else {
                        stage(checksum, result.lastModified ?: Clock.System.now())
                        "Imported ${result.fileName}"
                    }
                } catch (expected: Exception) {
                    // Includes a platform with no PDF or Excel parsing, whose message says so.
                    importMessageIsError = true
                    "${result.fileName}: ${expected.message}"
                }
            isImporting = false
        }
    }

    val xlsxFilePicker =
        rememberBinaryFilePicker(
            mimeTypes = listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        ) { result ->
            if (result != null) {
                stageBinaryFile(result) { checksum, lastModified ->
                    val parser = createXlsxParser()
                    val sheetName = parser.sheetNames(result.bytes).firstOrNull() ?: ""
                    val parsed = parser.parse(result.bytes, sheetName)
                    importEngine.createXlsxImport(
                        fileName = result.fileName,
                        headers = parsed.headers,
                        rows = parsed.rows,
                        fileChecksum = checksum,
                        fileLastModified = lastModified,
                        xlsxBytes = result.bytes,
                        xlsxWorksheetName = sheetName,
                    )
                }
            }
        }

    val pdfFilePicker =
        rememberBinaryFilePicker(mimeTypes = listOf("application/pdf")) { result ->
            if (result != null) {
                stageBinaryFile(result) { checksum, lastModified ->
                    importEngine.stagePdfImport(result.fileName, result.bytes, checksum, lastModified)
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
            title = "CSV Imports",
            importButtonLabel = "+ Import CSV",
            isImporting = isImporting,
            onImportClick = { filePicker.launch() },
            onStrategiesClick = onStrategiesClick,
        ) {
            TextButton(
                onClick = { xlsxFilePicker.launch() },
                enabled = !isImporting,
            ) {
                Text("+ Import Excel")
            }
            TextButton(
                onClick = { pdfFilePicker.launch() },
                enabled = !isImporting,
            ) {
                Text("+ Import PDF")
            }
        }

        ImportStatusMessage(message = importMessage, isError = importMessageIsError)

        Spacer(modifier = Modifier.height(16.dp))

        if (imports.isEmpty() && !isImporting) {
            ImportsEmptyMessage("No CSV files imported yet. Click '+ Import CSV' to add one or more.")
        } else {
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
                )
            }

            reimportAllScope?.let { scopedImported ->
                ReimportAllDialog(
                    files = scopedImported.map(StagedFile::Csv),
                    reimports = importDirectoryService.reimports,
                    pickers = ReimportPickerRepositories(accountRepository, categoryRepository, currencyRepository, personRepository),
                    onDismiss = { reimportAllScope = null },
                    onComplete = { reimportAllScope = null },
                )
            }

            unimportTarget?.let { target ->
                UnimportDialog(
                    csvImport = target,
                    csvImportRepository = csvImportRepository,
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
