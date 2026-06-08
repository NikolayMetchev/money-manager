package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Header row for an editable list item card: a title plus a remove button.
 */
@Composable
internal fun EditorCardHeader(
    title: String,
    removeContentDescription: String,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Close, contentDescription = removeContentDescription)
        }
    }
}

/**
 * Column picker for an account-name lookup column, with sample preview and required-field error.
 */
@Composable
internal fun AccountNameColumnDropdown(
    columnName: String,
    onColumnChanged: (String) -> Unit,
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    ColumnDropdown(
        columns = columns,
        selectedColumn = columnName.takeIf { it.isNotBlank() },
        onColumnSelected = onColumnChanged,
        label = "Column for account name",
        sampleValue = getSampleValue(columns, firstRow, columnName),
        enabled = enabled,
        isError = columnName.isBlank(),
    )
}
