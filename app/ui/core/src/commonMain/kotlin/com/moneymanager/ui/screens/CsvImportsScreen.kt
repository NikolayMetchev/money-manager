@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

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
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.security.MessageDigest

@Composable
fun CsvImportsScreen(
    csvImportRepository: CsvImportRepository,
    onImportClick: (CsvImportId) -> Unit,
    onStrategiesClick: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by csvImportRepository.getAllImports()
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
                        val checksum = computeSha256(result.content)
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
                            fileLastModified = result.lastModified,
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
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = import.originalFileName,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${import.rowCount} rows, ${import.columnCount} columns",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val localDateTime = import.importTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())
                Text(
                    text = "${localDateTime.date} ${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            import.fileChecksum?.let { checksum ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SHA-256: ${checksum.take(16)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DuplicateImportDialog(
    existingImport: CsvImport,
    onDismiss: () -> Unit,
    onViewExisting: () -> Unit,
) {
    val localDateTime = existingImport.importTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Already Imported") },
        text = {
            Text(
                "This file has already been imported as \"${existingImport.originalFileName}\" " +
                    "on ${localDateTime.date} at ${localDateTime.hour}:" +
                    "${localDateTime.minute.toString().padStart(2, '0')}.",
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

private fun computeSha256(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
}
