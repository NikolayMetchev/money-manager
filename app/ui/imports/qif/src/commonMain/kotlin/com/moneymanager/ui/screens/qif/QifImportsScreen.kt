@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.qif

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberMultipleFilePicker
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.timeline.ImportFileDateRange
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
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.qif.QifImportAllDialog
import com.moneymanager.ui.screens.qif.QifReimportAllDialog
import com.moneymanager.ui.screens.qif.dominantAccountType
import com.moneymanager.ui.screens.qif.toImportRecords
import com.moneymanager.ui.util.displayDate
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.sha256Hex
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
// Mirrors CsvImportsScreen (QIF reuses the CSV import engine); overlap is intentional.
@Suppress("LongParameterList", "DuplicatedCode")
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
                    var imported = 0
                    var skipped = 0
                    val failures = mutableListOf<String>()
                    for (result in results) {
                        try {
                            val checksum = sha256Hex(result.content)
                            if (qifImportRepository.findImportsByChecksum(checksum).isNotEmpty()) {
                                skipped++
                                continue
                            }
                            val parseResult = QifParser().parse(result.content)
                            importEngine.createQifImport(
                                fileName = result.fileName,
                                records = parseResult.toImportRecords(),
                                accountType = parseResult.dominantAccountType(),
                                fileChecksum = checksum,
                                fileLastModified = result.lastModified ?: Clock.System.now(),
                            )
                            imported++
                        } catch (expected: Exception) {
                            failures.add("${result.fileName}: ${expected.message}")
                        }
                    }
                    isImporting = false
                    importMessageIsError = failures.isNotEmpty()
                    importMessage =
                        buildString {
                            append("Imported $imported file${if (imported == 1) "" else "s"}")
                            if (skipped > 0) append(", skipped $skipped already imported")
                            if (failures.isNotEmpty()) {
                                append(", ${failures.size} failed: ")
                                append(failures.joinToString("; "))
                            }
                        }
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "QIF Imports",
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onStrategiesClick) {
                    Text("Strategies")
                }
                TextButton(
                    onClick = { filePicker.launch() },
                    enabled = !isImporting,
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    } else {
                        Text("+ Import QIF")
                    }
                }
            }
        }

        importMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = if (importMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (imports.isEmpty() && !isImporting) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No QIF files added yet. Click '+ Import QIF' to add one or more.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Split files into those still needing a strategy applied vs. already imported, so a large
            // set of files is easy to work through. The Unimported tab is the default/actionable one.
            val unimported = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt == null } }
            val importedList = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt != null } }
            val ignoredList = remember(imports) { imports.filter { it.ignored } }
            var selectedTab by remember { mutableStateOf(0) }

            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Unimported (${unimported.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Imported (${importedList.size})") },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Ignored (${ignoredList.size})") },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            var showImportAll by remember { mutableStateOf(false) }
            if (selectedTab == 0 && unimported.isNotEmpty()) {
                Button(
                    onClick = { showImportAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import all (${unimported.size})")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            var showReimportAll by remember { mutableStateOf(false) }
            if (selectedTab == 1 && importedList.isNotEmpty()) {
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
                    0 -> unimported
                    1 -> importedList
                    else -> ignoredList
                }
            if (shown.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            when (selectedTab) {
                                0 -> "All files have been imported."
                                1 -> "No files imported yet."
                                else -> "No ignored files."
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val ignoredTab = selectedTab == 2
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id.toString() }) { import ->
                        QifImportCard(
                            import = import,
                            dateRange = dateRanges[import.id.id.toString()],
                            onClick = { onImportClick(import.id) },
                            ignored = ignoredTab,
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

// Mirrors CsvImportCard by design.
@Suppress("DuplicatedCode")
@Composable
private fun QifImportCard(
    import: QifImport,
    dateRange: ImportFileDateRange?,
    onClick: () -> Unit,
    ignored: Boolean,
    onSetIgnored: (Boolean) -> Unit,
) {
    val isImported = import.lastAppliedAt != null
    val containerColor =
        if (isImported) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        }
    val metadataColor =
        if (isImported) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = import.originalFileName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!ignored) {
                        QifImportStateBadge(isImported = isImported)
                    }
                    // Only unimported files can be ignored; the Ignored tab offers Restore.
                    if (ignored) {
                        TextButton(onClick = { onSetIgnored(false) }) { Text("Restore") }
                    } else if (!isImported) {
                        TextButton(onClick = { onSetIgnored(true) }) { Text("Ignore") }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        buildString {
                            append("${import.recordCount} records")
                            append(" · ${import.accountType}")
                            if (import.unsupportedCount > 0) {
                                append(" · ${import.unsupportedCount} unsupported")
                            }
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
                Text(
                    text = "Added ${import.importTimestamp.displayDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
            }
            if (import.errorCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${import.errorCount} error${if (import.errorCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val lastAppliedAt = import.lastAppliedAt
            Text(
                text =
                    if (lastAppliedAt != null) {
                        buildString {
                            append(if (import.applicationCount > 1) "Latest import on " else "Imported on ")
                            append(lastAppliedAt.displayDateTime())
                            import.lastAppliedStrategyName?.takeIf(String::isNotBlank)?.let { strategyName ->
                                append(" via ")
                                append(strategyName)
                            }
                        }
                    } else {
                        "Not imported yet"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (isImported) metadataColor else MaterialTheme.colorScheme.secondary,
            )
            if (dateRange != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text =
                        "Transactions ${dateRange.earliest.displayDate()} → ${dateRange.latest.displayDate()} " +
                            "(${dateRange.transactionCount})",
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
            }
        }
    }
}

@Composable
private fun QifImportStateBadge(isImported: Boolean) {
    val containerColor =
        if (isImported) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        if (isImported) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Box(
        modifier =
            Modifier
                .background(color = containerColor, shape = MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isImported) "Imported" else "Unimported",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
