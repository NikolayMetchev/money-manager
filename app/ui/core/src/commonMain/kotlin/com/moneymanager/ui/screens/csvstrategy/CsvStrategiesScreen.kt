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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.compose.filepicker.rememberFileSaver
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.service.ImportParseResult
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import kotlinx.coroutines.launch
import nl.jacobras.humanreadable.HumanReadable

@Suppress("UnusedParameter") // onStrategyClick retained for future extensibility
@Composable
fun CsvStrategiesScreen(
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvImportRepository: CsvImportRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySourceQueries: EntitySourceQueries,
    deviceId: DeviceId,
    csvStrategyExportService: CsvStrategyExportService,
    appVersion: AppVersion,
    onStrategyClick: (CsvImportStrategy) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val strategies by csvImportStrategyRepository.getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    // Edit flow state
    var strategyToEdit by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var showSelectCsvDialog by remember { mutableStateOf(false) }
    var selectedCsvImport by remember { mutableStateOf<CsvImport?>(null) }
    var csvRows by remember { mutableStateOf<List<CsvRow>>(emptyList()) }
    var isLoadingRows by remember { mutableStateOf(false) }

    // Export state
    var strategyToExport by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Import state
    var importParseResult by remember { mutableStateOf<ImportParseResult?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // File saver for export
    val fileSaver =
        rememberFileSaver(
            mimeType = "application/json",
            onResult = { result ->
                if (result?.success == true) {
                    strategyToExport = null
                    exportJson = null
                }
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
                            val parseResult = csvStrategyExportService.parseExport(export)
                            importParseResult = parseResult
                            importError = null
                        } catch (expected: Exception) {
                            importError = "Failed to parse file: ${expected.message}"
                            importParseResult = null
                        }
                    }
                }
            },
        )

    // Handle export when strategy is selected
    LaunchedEffect(strategyToExport) {
        val strategy = strategyToExport
        if (strategy != null && exportJson == null) {
            @Suppress("TooGenericExceptionCaught")
            try {
                val export = csvStrategyExportService.toExport(strategy, appVersion)
                val json = CsvStrategyExportCodec.encode(export)
                exportJson = json
                val fileName = "${strategy.name.replace(Regex("[^a-zA-Z0-9-_]"), "_")}.json"
                fileSaver.launch(fileName, json)
            } catch (expected: Exception) {
                strategyToExport = null
                exportJson = null
            }
        }
    }

    // Load CSV rows when a CSV import is selected
    LaunchedEffect(selectedCsvImport) {
        val csvImport = selectedCsvImport
        if (csvImport != null) {
            isLoadingRows = true
            try {
                csvRows = csvImportRepository.getImportRows(csvImport.id, limit = 100, offset = 0)
            } finally {
                isLoadingRows = false
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
                                    strategyToEdit = strategy
                                    showSelectCsvDialog = true
                                },
                                onExportClick = {
                                    strategyToExport = strategy
                                },
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

    // Show CSV import selector dialog
    if (showSelectCsvDialog && strategyToEdit != null) {
        SelectCsvImportDialog(
            csvImportRepository = csvImportRepository,
            onCsvSelected = { csvImport ->
                selectedCsvImport = csvImport
                showSelectCsvDialog = false
            },
            onDismiss = {
                showSelectCsvDialog = false
                strategyToEdit = null
            },
        )
    }

    // Show edit strategy dialog when CSV import is selected
    val currentCsvImport = selectedCsvImport
    val currentStrategyToEdit = strategyToEdit
    if (currentCsvImport != null && currentStrategyToEdit != null && !isLoadingRows) {
        CreateCsvStrategyDialog(
            csvImportStrategyRepository = csvImportStrategyRepository,
            csvAccountMappingRepository = csvAccountMappingRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            attributeTypeRepository = attributeTypeRepository,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            csvColumns = currentCsvImport.columns,
            rows = csvRows,
            onDismiss = {
                selectedCsvImport = null
                strategyToEdit = null
                csvRows = emptyList()
            },
            existingStrategy = currentStrategyToEdit,
        )
    }

    // Show import dialog when a strategy file has been parsed
    val currentParseResult = importParseResult
    if (currentParseResult != null) {
        ImportStrategyDialog(
            parseResult = currentParseResult,
            csvImportStrategyRepository = csvImportStrategyRepository,
            csvStrategyExportService = csvStrategyExportService,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
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
                    onClick = onExportClick,
                ) {
                    Text(
                        text = "\u2B71",
                        style = MaterialTheme.typography.titleMedium,
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
