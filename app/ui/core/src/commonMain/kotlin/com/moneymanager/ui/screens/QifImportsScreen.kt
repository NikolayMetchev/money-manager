@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

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
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportId
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
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.qif.QifParser
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.qif.QifImportAllDialog
import com.moneymanager.ui.screens.qif.dominantAccountType
import com.moneymanager.ui.screens.qif.toImportRecords
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.sha256Hex
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
// Mirrors CsvImportsScreen (QIF reuses the CSV import engine); overlap is intentional.
@Suppress("LongParameterList", "DuplicatedCode")
fun QifImportsScreen(
    qifImportRepository: QifImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    transactionRepository: TransactionRepository,
    attributeTypeRepository: AttributeTypeRepository,
    settingsRepository: SettingsRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    onImportClick: (QifImportId) -> Unit,
    onStrategiesClick: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by qifImportRepository
        .getAllImports()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
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
                            qifImportRepository.createImport(
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
            val unimported = remember(imports) { imports.filter { it.lastAppliedAt == null } }
            val importedList = remember(imports) { imports.filter { it.lastAppliedAt != null } }
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

            if (showImportAll) {
                QifImportAllDialog(
                    unimported = unimported,
                    csvImportStrategyRepository = csvImportStrategyRepository,
                    csvAccountMappingRepository = csvAccountMappingRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    transactionRepository = transactionRepository,
                    qifImportRepository = qifImportRepository,
                    attributeTypeRepository = attributeTypeRepository,
                    settingsRepository = settingsRepository,
                    maintenance = maintenance,
                    entitySource = entitySource,
                    onDismiss = { showImportAll = false },
                    onComplete = { showImportAll = false },
                )
            }

            val shown = if (selectedTab == 0) unimported else importedList
            if (shown.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedTab == 0) "All files have been imported." else "No files imported yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id.toString() }) { import ->
                        QifImportCard(
                            import = import,
                            onClick = { onImportClick(import.id) },
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
    onClick: () -> Unit,
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
                )
                QifImportStateBadge(isImported = isImported)
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
