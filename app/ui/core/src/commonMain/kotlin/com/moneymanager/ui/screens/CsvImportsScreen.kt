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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.csv.CsvParseOptions
import com.moneymanager.csv.CsvParser
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.sha256Hex
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
fun CsvImportsScreen(
    csvImportRepository: CsvImportRepository,
    onImportClick: (CsvImportId) -> Unit,
    onStrategiesClick: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by csvImportRepository
        .getAllImports()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var duplicateImport by remember { mutableStateOf<CsvImport?>(null) }

    val filePicker =
        rememberFilePicker(
            mimeTypes = listOf("text/csv", "text/plain", "text/comma-separated-values"),
        ) { result ->
            if (result != null) {
                isImporting = true
                importError = null
                scope.launch {
                    try {
                        val checksum = sha256Hex(result.content)
                        val existing = csvImportRepository.findImportsByChecksum(checksum)
                        if (existing.isNotEmpty()) {
                            duplicateImport = existing.first()
                            isImporting = false
                            return@launch
                        }
                        val parser = CsvParser()
                        val delimiter = parser.detectDelimiter(result.content)
                        val parseResult =
                            parser.parse(
                                result.content,
                                CsvParseOptions(delimiter = delimiter),
                            )
                        csvImportRepository.createImport(
                            fileName = result.fileName,
                            headers = parseResult.headers,
                            rows = parseResult.rows,
                            fileChecksum = checksum,
                            fileLastModified = result.lastModified ?: Clock.System.now(),
                        )
                    } catch (expected: Exception) {
                        importError = "Failed to import CSV: ${expected.message}"
                    } finally {
                        isImporting = false
                    }
                }
            }
        }

    duplicateImport?.let { existing ->
        DuplicateImportDialog(
            existingImport = existing,
            onDismiss = { duplicateImport = null },
            onViewExisting = {
                duplicateImport = null
                onImportClick(existing.id)
            },
        )
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
                text = "CSV Imports",
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onStrategiesClick,
                ) {
                    Text("Strategies")
                }
                TextButton(
                    onClick = { filePicker.launch() },
                    enabled = !isImporting,
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                        )
                    } else {
                        Text("+ Import CSV")
                    }
                }
            }
        }

        importError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
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
                    text = "No CSV files imported yet. Click '+ Import CSV' to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(imports) { import ->
                    CsvImportCard(
                        import = import,
                        onClick = { onImportClick(import.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CsvImportCard(
    import: CsvImport,
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
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = import.originalFileName,
                    style = MaterialTheme.typography.titleMedium,
                )
                ImportStateBadge(isImported = isImported)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${import.rowCount} rows, ${import.columnCount} columns",
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
                            if (import.applicationCount > 1) {
                                append("Latest import on ")
                            } else {
                                append("Imported on ")
                            }
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
                color =
                    if (isImported) {
                        metadataColor
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
            )
            if (isImported && import.lastAppliedStrategyName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Strategy information unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
            }
            if (isImported && import.applicationCount > 1) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Applied ${import.applicationCount} times",
                    style = MaterialTheme.typography.bodySmall,
                    color = metadataColor,
                )
            }
        }
    }
}

@Composable
private fun ImportStateBadge(isImported: Boolean) {
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
                .background(
                    color = containerColor,
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isImported) "Imported" else "Unimported",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@Composable
private fun DuplicateImportDialog(
    existingImport: CsvImport,
    onDismiss: () -> Unit,
    onViewExisting: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Already Imported") },
        text = {
            Text(
                "This file has already been imported as \"${existingImport.originalFileName}\" " +
                    "on ${existingImport.importTimestamp.displayDateTime()}.",
            )
        },
        confirmButton = {
            TextButton(onClick = onViewExisting) {
                Text("View Existing")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        },
    )
}
