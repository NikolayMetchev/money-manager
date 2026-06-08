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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Amount & Date tab: amount + fee, currency, timezone, and date/time parsing.
 */
@Composable
internal fun AmountDateTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
    currencyRepository: CurrencyRepository,
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
        Text("Date Column", style = MaterialTheme.typography.titleSmall)
        ColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.dateColumnName,
            onColumnSelected = { state.dateColumnName = it },
            label = "Column containing transaction date",
            sampleValue = getSampleValue(csvColumns, firstRow, state.dateColumnName),
            enabled = enabled,
            isError = state.dateColumnName == null,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = state.dateFormat,
            onValueChange = { state.dateFormat = it },
            label = { Text("Date Format (e.g., dd/MM/yyyy)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            supportingText = {
                getSampleValue(csvColumns, firstRow, state.dateColumnName)?.let {
                    Text("Sample: $it")
                }
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
        if (state.timeColumnName != null && state.dateTimeFormat.isBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = state.timeFormat,
                onValueChange = { state.timeFormat = it },
                label = { Text("Time Format (e.g., HH:mm:ss)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                supportingText = {
                    getSampleValue(csvColumns, firstRow, state.timeColumnName)?.let {
                        Text("Sample: $it")
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = state.dateTimeFormat,
            onValueChange = { state.dateTimeFormat = it },
            label = { Text("Combined date+time format (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            supportingText = {
                Text(
                    "When set, the date column holds both date and time " +
                        "(e.g., yyyy-MM-dd HH:mm:ss) and the separate time column is ignored.",
                )
            },
        )
    }
}
