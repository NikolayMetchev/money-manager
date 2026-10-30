package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.importFileKind
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.humanreadable.HumanReadable
import com.moneymanager.importengineapi.deleteCsvStrategy
import com.moneymanager.presenter.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.launch

/**
 * Dialog for deleting a CSV import strategy.
 */
@Composable
fun DeleteCsvStrategyDialog(
    strategy: CsvImportStrategy,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = { Text("⚠️") },
        title = { Text("Delete Strategy?") },
        text = {
            Column {
                Text("Are you sure you want to delete the strategy \"${strategy.name}\"?")
                Text(
                    "This action cannot be undone.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDeleting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            importEngine.deleteCsvStrategy(strategy.id)
                            onDismiss()
                        } catch (expected: Exception) {
                            errorMessage = "Failed to delete: ${expected.message}"
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
    )
}

/** The kind of staged file a strategy reads, and so the sample files its editor offers. */
enum class SampleFileKind { CSV, PDF, QIF }

/**
 * Dialog for selecting a staged import to use as sample data when editing a strategy: a PDF strategy reads PDF
 * imports, a QIF strategy (over the fixed QIF columns) QIF files, and any other strategy every other import.
 */
@Composable
fun SelectCsvImportDialog(
    csvImportRepository: CsvImportReadRepository,
    onCsvSelected: (CsvImport) -> Unit,
    onDismiss: () -> Unit,
    kind: SampleFileKind = SampleFileKind.CSV,
) {
    val allImports by csvImportRepository
        .getAllImports()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    var pdfImportIds by remember { mutableStateOf<Set<CsvImportId>>(emptySet()) }
    LaunchedEffect(allImports) { pdfImportIds = csvImportRepository.getPdfImportIds() }
    val imports =
        allImports.filter {
            when {
                it.id in pdfImportIds -> SampleFileKind.PDF
                importFileKind(it.originalFileName) == ImportFileKind.QIF -> SampleFileKind.QIF
                else -> SampleFileKind.CSV
            } == kind
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select $kind File") },
        text = {
            if (imports.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No $kind files available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Import a $kind file first to use as sample data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(imports) { csvImport ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onCsvSelected(csvImport) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = csvImport.originalFileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${csvImport.rowCount} rows, ${csvImport.columnCount} columns",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Imported ${HumanReadable.timeAgo(csvImport.importTimestamp)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
