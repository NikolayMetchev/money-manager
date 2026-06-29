package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.DateFormatDetector
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Amount & Date tab: amount + fee, currency, timezone, and date/time parsing.
 */
@Composable
internal fun AmountDateTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
    currencyRepository: CurrencyReadRepository,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Amount Column", style = MaterialTheme.typography.titleSmall)
        ColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.amountColumnName,
            onColumnSelected = { state.amountColumnName = it },
            label = "Column containing transaction amount",
            sampleValue = getSampleValue(csvColumns, firstRow, state.amountColumnName),
            enabled = enabled,
            isError = state.amountColumnName == null,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.flipAccountsOnPositive,
                onCheckedChange = { state.flipAccountsOnPositive = it },
                enabled = enabled,
            )
            Text(
                "Swap accounts when amount is positive",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Fee column (optional)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Added to the amount's magnitude when the conditions below hold",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionalColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.feeColumnName,
            onColumnSelected = { state.feeColumnName = it },
            label = "Column containing fee amount",
            sampleValue = getSampleValue(csvColumns, firstRow, state.feeColumnName),
            enabled = enabled,
        )
        if (state.feeColumnName != null) {
            Spacer(modifier = Modifier.height(4.dp))
            RowConditionsEditor(
                conditions = state.feeConditions,
                onConditionsChanged = { state.feeConditions = it },
                columns = csvColumns,
                enabled = enabled,
                title = "Apply fee when (all conditions match; none = always)",
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Currency", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.currencyMode == CurrencyMode.HARDCODED,
                onClick = { state.currencyMode = CurrencyMode.HARDCODED },
                enabled = enabled,
            )
            Text("Fixed Currency", modifier = Modifier.padding(end = 16.dp))
            RadioButton(
                selected = state.currencyMode == CurrencyMode.FROM_COLUMN,
                onClick = { state.currencyMode = CurrencyMode.FROM_COLUMN },
                enabled = enabled,
            )
            Text("From CSV Column")
        }
        when (state.currencyMode) {
            CurrencyMode.HARDCODED ->
                CurrencyPicker(
                    selectedCurrencyId = state.selectedCurrencyId,
                    onCurrencySelected = { state.selectedCurrencyId = it },
                    label = "Select Currency",
                    currencyRepository = currencyRepository,
                    enabled = enabled,
                    isError = state.selectedCurrencyId == null,
                )
            CurrencyMode.FROM_COLUMN ->
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = state.currencyColumnName,
                    onColumnSelected = { state.currencyColumnName = it },
                    label = "Column containing currency code",
                    sampleValue = getSampleValue(csvColumns, firstRow, state.currencyColumnName),
                    enabled = enabled,
                    isError = state.currencyColumnName == null,
                )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Timezone", style = MaterialTheme.typography.titleSmall)
        Text(
            "Timezone for interpreting date/time values",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.timezoneMode == TimezoneMode.HARDCODED,
                onClick = { state.timezoneMode = TimezoneMode.HARDCODED },
                enabled = enabled,
            )
            Text("Fixed Timezone", modifier = Modifier.padding(end = 16.dp))
            RadioButton(
                selected = state.timezoneMode == TimezoneMode.FROM_COLUMN,
                onClick = { state.timezoneMode = TimezoneMode.FROM_COLUMN },
                enabled = enabled,
            )
            Text("From CSV Column")
        }
        when (state.timezoneMode) {
            TimezoneMode.HARDCODED ->
                TimezonePicker(
                    selectedTimezone = state.selectedTimezone,
                    onTimezoneSelected = { state.selectedTimezone = it },
                    enabled = enabled,
                )
            TimezoneMode.FROM_COLUMN ->
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = state.timezoneColumnName,
                    onColumnSelected = { state.timezoneColumnName = it },
                    label = "Column containing timezone ID",
                    sampleValue = getSampleValue(csvColumns, firstRow, state.timezoneColumnName),
                    enabled = enabled,
                    isError = state.timezoneColumnName == null,
                )
        }

        Spacer(modifier = Modifier.height(16.dp))
        DateTimeSection(state, csvColumns, rows, firstRow, enabled)
    }
}

/**
 * Date/time parsing controls. A single checkbox toggles between the two mutually exclusive layouts —
 * a combined date+time column or separate date/time columns — so only the fields relevant to the
 * chosen layout are shown. Format fields auto-fill from the selected column's sample values via
 * [DateFormatDetector] until the user edits them; the "Auto-detect" action re-runs detection on demand.
 */
@Composable
private fun DateTimeSection(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
) {
    fun columnSamples(columnName: String?): List<String> {
        val index = csvColumns.find { it.originalName == columnName }?.columnIndex ?: return emptyList()
        return rows.mapNotNull { it.values.getOrNull(index) }
    }

    // Auto-fill the active format from the chosen column's samples until the user takes over.
    LaunchedEffect(state.dateColumnName, state.dateTimeInOneColumn, rows) {
        if (state.dateTimeInOneColumn) {
            if (!state.combinedFormatTouched) {
                DateFormatDetector.detectDateTime(columnSamples(state.dateColumnName))?.let { state.dateTimeFormat = it }
            }
        } else if (!state.dateFormatTouched) {
            DateFormatDetector.detectDate(columnSamples(state.dateColumnName))?.let { state.dateFormat = it }
        }
    }
    LaunchedEffect(state.timeColumnName, rows) {
        if (!state.dateTimeInOneColumn && state.timeColumnName != null && !state.timeFormatTouched) {
            DateFormatDetector.detectTime(columnSamples(state.timeColumnName))?.let { state.timeFormat = it }
        }
    }

    Text("Date & Time", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.dateTimeInOneColumn,
            onCheckedChange = { state.dateTimeInOneColumn = it },
            enabled = enabled,
        )
        Text(
            "Date and time are in a single column",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    ColumnDropdown(
        columns = csvColumns,
        selectedColumn = state.dateColumnName,
        onColumnSelected = { state.dateColumnName = it },
        label = if (state.dateTimeInOneColumn) "Column containing date and time" else "Column containing transaction date",
        sampleValue = getSampleValue(csvColumns, firstRow, state.dateColumnName),
        enabled = enabled,
        isError = state.dateColumnName == null,
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (state.dateTimeInOneColumn) {
        FormatField(
            value = state.dateTimeFormat,
            onValueChange = {
                state.dateTimeFormat = it
                state.combinedFormatTouched = true
            },
            label = "Date+time format (e.g., yyyy-MM-dd HH:mm:ss)",
            sample = getSampleValue(csvColumns, firstRow, state.dateColumnName),
            enabled = enabled,
            isError = state.dateTimeFormat.isBlank(),
            onAutoDetect = {
                DateFormatDetector.detectDateTime(columnSamples(state.dateColumnName))?.let { state.dateTimeFormat = it }
            },
        )
    } else {
        FormatField(
            value = state.dateFormat,
            onValueChange = {
                state.dateFormat = it
                state.dateFormatTouched = true
            },
            label = "Date format (e.g., dd/MM/yyyy)",
            sample = getSampleValue(csvColumns, firstRow, state.dateColumnName),
            enabled = enabled,
            isError = state.dateFormat.isBlank(),
            onAutoDetect = {
                DateFormatDetector.detectDate(columnSamples(state.dateColumnName))?.let { state.dateFormat = it }
            },
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Time Column (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Select if time is in a separate column",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OptionalColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.timeColumnName,
            onColumnSelected = { state.timeColumnName = it },
            label = "Column containing transaction time",
            sampleValue = getSampleValue(csvColumns, firstRow, state.timeColumnName),
            enabled = enabled,
        )
        if (state.timeColumnName != null) {
            Spacer(modifier = Modifier.height(4.dp))
            FormatField(
                value = state.timeFormat,
                onValueChange = {
                    state.timeFormat = it
                    state.timeFormatTouched = true
                },
                label = "Time format (e.g., HH:mm:ss)",
                sample = getSampleValue(csvColumns, firstRow, state.timeColumnName),
                enabled = enabled,
                isError = state.timeFormat.isBlank(),
                onAutoDetect = {
                    DateFormatDetector.detectTime(columnSamples(state.timeColumnName))?.let { state.timeFormat = it }
                },
            )
        }
    }
}

/**
 * A date/time format text field with a "Sample: …" hint and an inline "Auto-detect" action that
 * re-guesses the pattern from the column's values.
 */
@Composable
private fun FormatField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    sample: String?,
    enabled: Boolean,
    isError: Boolean,
    onAutoDetect: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        trailingIcon = {
            TextButton(onClick = onAutoDetect, enabled = enabled) {
                Text("Auto-detect")
            }
        },
        supportingText = {
            sample?.let { Text("Sample: $it") }
        },
    )
}
