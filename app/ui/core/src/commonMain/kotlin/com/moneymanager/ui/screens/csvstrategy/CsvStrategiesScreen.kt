@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.compose.filepicker.rememberFileSaver
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.domain.CsvImportParseResult
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.QifImportRepository
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.jacobras.humanreadable.HumanReadable

@Composable
fun CsvStrategiesScreen(
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvImportRepository: CsvImportRepository,
    qifImportRepository: QifImportRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    personRepository: PersonRepository,
    csvStrategyImportExport: CsvStrategyImportExport,
    appVersion: AppVersion,
    onStrategyClick: (CsvImportStrategy) -> Unit = {},
    onBack: () -> Unit = {},
    onEditStrategy: (CsvImportStrategyId, CsvImportId) -> Unit = { _, _ -> },
    onEditQifStrategy: (CsvImportStrategyId, QifImportId) -> Unit = { _, _ -> },
    onAuditHistoryClick: (CsvImportStrategy) -> Unit = {},
) {
    val strategies by csvImportStrategyRepository
        .getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    // Edit flow state: pick a sample file to edit the strategy against, then navigate to the editor.
    // QIF strategies (columns are the fixed QIF fields) need a QIF file as sample data, not a CSV one.
    var strategyToEdit by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var showSelectFileDialog by remember { mutableStateOf(false) }

    // Export state
    var strategyPendingExportPrompt by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var strategyToExport by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var includeAccountMappingsInExport by remember { mutableStateOf<Boolean?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Import state
    var importParseResult by remember { mutableStateOf<CsvImportParseResult?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // File saver for export
    val fileSaver =
        rememberFileSaver(
            onResult = {
                strategyToExport = null
                includeAccountMappingsInExport = null
                exportJson = null
            },
        )

    // File picker for import
    val filePicker =
        rememberFilePicker(
            mimeTypes = listOf("application/json"),
            onResult = { result ->
                if (result != null) {
                    scope.launch {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            val jsonContent = result.content
                            val export = CsvStrategyExportCodec.decode(jsonContent)
                            val parseResult = csvStrategyImportExport.parseExport(export)
                            importParseResult = parseResult
                            importError = null
                            exportError = null
                        } catch (expected: Exception) {
                            importError = "Failed to parse file: ${expected.message}"
                            importParseResult = null
                        }
                    }
                }
            },
        )

    // Handle export when strategy is selected
    LaunchedEffect(strategyToExport, includeAccountMappingsInExport) {
        val strategy = strategyToExport
        val includeAccountMappings = includeAccountMappingsInExport
        if (strategy != null && includeAccountMappings != null && exportJson == null) {
            @Suppress("TooGenericExceptionCaught")
            try {
                exportError = null
                val persistedAccountMappings =
                    if (includeAccountMappings) {
                        csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
                    } else {
                        emptyList()
                    }
                val export =
                    csvStrategyImportExport.toExport(
                        strategy = strategy,
                        appVersion = appVersion,
                        accountMappings = persistedAccountMappings.takeIf { includeAccountMappings },
                    )
                val json = CsvStrategyExportCodec.encode(export)
                exportJson = json
                val fileName = "${strategy.name.replace(Regex("[^a-zA-Z0-9-_]"), "_")}.json"
                fileSaver.launch(fileName, json)
            } catch (expected: Exception) {
                strategyToExport = null
                includeAccountMappingsInExport = null
                exportJson = null
                exportError = "Failed to export: ${expected.message}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Text("<", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = "Import Strategies",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                // Import button
                IconButton(onClick = { filePicker.launch() }) {
                    Text(
                        text = "\u2B73",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            // Show import error if any
            importError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            exportError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (strategies.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No import strategies yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To create a strategy, go to CSV Imports,\nselect a CSV file, and click \"Create Strategy\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(strategies) { strategy ->
                            CsvStrategyCard(
                                strategy = strategy,
                                csvImportStrategyRepository = csvImportStrategyRepository,
                                onEditClick = {
                                    onStrategyClick(strategy)
                                    strategyToEdit = strategy
                                    showSelectFileDialog = true
                                },
                                onExportClick = {
                                    strategyPendingExportPrompt = strategy
                                },
                                onAuditClick = { onAuditHistoryClick(strategy) },
                            )
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    val currentStrategyPendingExportPrompt = strategyPendingExportPrompt
    if (currentStrategyPendingExportPrompt != null) {
        ExportAccountMappingsDialog(
            strategy = currentStrategyPendingExportPrompt,
            onExport = { includeAccountMappings ->
                strategyPendingExportPrompt = null
                exportJson = null
                exportError = null
                strategyToExport = currentStrategyPendingExportPrompt
                includeAccountMappingsInExport = includeAccountMappings
            },
            onDismiss = {
                strategyPendingExportPrompt = null
            },
        )
    }

    // Show the sample-file selector, then navigate to the editor. QIF strategies (whose identification
    // columns are exactly the fixed QIF fields) pick a QIF file and route to the QIF editor; all other
    // strategies pick a CSV file.
    val editingStrategy = strategyToEdit
    if (showSelectFileDialog && editingStrategy != null) {
        val dismiss = {
            showSelectFileDialog = false
            strategyToEdit = null
        }
        if (editingStrategy.isQifStrategy()) {
            SelectQifImportDialog(
                qifImportRepository = qifImportRepository,
                onQifSelected = { qifImport ->
                    dismiss()
                    onEditQifStrategy(editingStrategy.id, qifImport.id)
                },
                onDismiss = dismiss,
            )
        } else {
            SelectCsvImportDialog(
                csvImportRepository = csvImportRepository,
                onCsvSelected = { csvImport ->
                    dismiss()
                    onEditStrategy(editingStrategy.id, csvImport.id)
                },
                onDismiss = dismiss,
            )
        }
    }

    // Show import dialog when a strategy file has been parsed
    val currentParseResult = importParseResult
    if (currentParseResult != null) {
        ImportStrategyDialog(
            parseResult = currentParseResult,
            csvImportStrategyRepository = csvImportStrategyRepository,
            csvAccountMappingRepository = csvAccountMappingRepository,
            csvStrategyImportExport = csvStrategyImportExport,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            personRepository = personRepository,
            onDismiss = {
                importParseResult = null
                importError = null
            },
            onImportSuccess = {
                importParseResult = null
                importError = null
            },
        )
    }
}

@Composable
fun CsvStrategyCard(
    strategy: CsvImportStrategy,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    onEditClick: () -> Unit,
    onExportClick: () -> Unit,
    onAuditClick: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strategy.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${strategy.identificationColumns.size} identification columns",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${strategy.fieldMappings.size} field mappings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Updated ${HumanReadable.timeAgo(strategy.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(
                    onClick = onAuditClick,
                    modifier = Modifier.semantics { contentDescription = "View audit history" },
                ) {
                    Text(
                        text = "🕒",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = onExportClick,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Export",
                    )
                }
                IconButton(
                    onClick = onEditClick,
                ) {
                    Text(
                        text = "\u270F\uFE0F",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = { showDeleteDialog = true },
                ) {
                    Text(
                        text = "\uD83D\uDDD1\uFE0F",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteCsvStrategyDialog(
            strategy = strategy,
            csvImportStrategyRepository = csvImportStrategyRepository,
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * A QIF strategy is a CSV strategy whose identification columns are (a non-empty subset of) the fixed
 * QIF fields, so it is edited against a QIF file rather than a CSV one.
 */
private fun CsvImportStrategy.isQifStrategy(): Boolean =
    identificationColumns.isNotEmpty() && identificationColumns.all { it in QifCsvAdapter.headers }
