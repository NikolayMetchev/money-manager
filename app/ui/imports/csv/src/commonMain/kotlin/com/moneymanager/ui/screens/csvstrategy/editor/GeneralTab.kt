package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.ui.screens.csvstrategy.findRowWithBlankColumn
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * General tab: strategy name, identification columns, and the description column + fallback.
 */
@Composable
internal fun GeneralTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { state.name = it },
            label = { Text("Strategy Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = state.name.isBlank(),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Identification Columns", style = MaterialTheme.typography.titleSmall)
        Text(
            "Select columns that uniquely identify this CSV format",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        IdentificationColumnsSelector(
            columns = csvColumns,
            selectedColumns = state.identificationColumns,
            onSelectionChanged = { state.identificationColumns = it },
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = state.fileNamePattern,
            onValueChange = { state.fileNamePattern = it },
            label = { Text("File name pattern (regex, optional)") },
            supportingText = {
                Text("Matches this strategy by file name when several strategies share the same columns")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Description Column", style = MaterialTheme.typography.titleSmall)
        ColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.descriptionColumnName,
            onColumnSelected = { state.descriptionColumnName = it },
            label = "Column containing transaction description",
            sampleValue = getSampleValue(csvColumns, firstRow, state.descriptionColumnName),
            enabled = enabled,
            isError = state.descriptionColumnName == null,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Fallback column (when primary is empty)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Find a row where the primary column is blank to show a relevant sample
        val descriptionFallbackSampleRow = findRowWithBlankColumn(csvColumns, rows, state.descriptionColumnName)
        OptionalColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.descriptionFallbackColumns.firstOrNull(),
            onColumnSelected = { selected ->
                state.descriptionFallbackColumns = if (selected != null) listOf(selected) else emptyList()
            },
            label = "Fallback column for description",
            sampleValue = getSampleValue(csvColumns, descriptionFallbackSampleRow, state.descriptionFallbackColumns.firstOrNull()),
            enabled = enabled,
        )
    }
}
