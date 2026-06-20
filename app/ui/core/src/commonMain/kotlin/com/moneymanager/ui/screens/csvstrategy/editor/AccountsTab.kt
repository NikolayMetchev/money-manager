package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.screens.csvstrategy.findRowWithBlankColumn
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Accounts tab: how the source and target accounts are resolved per row.
 */
@Composable
internal fun AccountsTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    rows: List<CsvRow>,
    firstRow: CsvRow?,
    enabled: Boolean,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    personRepository: PersonRepository,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Source Account (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Can also be chosen each time you apply this strategy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.sourceAccountMode == SourceAccountMode.FIXED_ACCOUNT,
                onClick = { state.sourceAccountMode = SourceAccountMode.FIXED_ACCOUNT },
                enabled = enabled,
            )
            Text("Fixed Account", modifier = Modifier.padding(end = 16.dp))
            RadioButton(
                selected = state.sourceAccountMode == SourceAccountMode.TEMPLATE,
                onClick = { state.sourceAccountMode = SourceAccountMode.TEMPLATE },
                enabled = enabled,
            )
            Text("From Column (Template)")
        }
        when (state.sourceAccountMode) {
            SourceAccountMode.FIXED_ACCOUNT ->
                AccountPicker(
                    selectedAccountId = state.selectedAccountId,
                    onAccountSelected = { state.selectedAccountId = it },
                    label = "Select Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = enabled,
                )
            SourceAccountMode.TEMPLATE ->
                TemplateAccountMappingEditor(
                    columnName = state.sourceTemplateColumnName,
                    onColumnChanged = { state.sourceTemplateColumnName = it },
                    prefix = state.sourceTemplatePrefix,
                    onPrefixChanged = { state.sourceTemplatePrefix = it },
                    suffix = state.sourceTemplateSuffix,
                    onSuffixChanged = { state.sourceTemplateSuffix = it },
                    columns = csvColumns,
                    firstRow = firstRow,
                    enabled = enabled,
                )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Target Account", style = MaterialTheme.typography.titleSmall)
        Text(
            "How the target account is resolved for each row",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TargetAccountModeSelector(
            selected = state.targetAccountMode,
            onSelected = { state.targetAccountMode = it },
            enabled = enabled,
        )

        when (state.targetAccountMode) {
            TargetAccountMode.DIRECT_LOOKUP, TargetAccountMode.REGEX_MATCH -> {
                Spacer(modifier = Modifier.height(4.dp))
                ColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = state.targetAccountColumnName,
                    onColumnSelected = { state.targetAccountColumnName = it },
                    label = "Column for payee/counterparty name",
                    sampleValue = getSampleValue(csvColumns, firstRow, state.targetAccountColumnName),
                    enabled = enabled,
                    isError = state.targetAccountColumnName == null,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Fallback column (when primary is empty)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fallbackSampleRow = findRowWithBlankColumn(csvColumns, rows, state.targetAccountColumnName)
                OptionalColumnDropdown(
                    columns = csvColumns,
                    selectedColumn = state.targetAccountFallbackColumns.firstOrNull(),
                    onColumnSelected = { selected ->
                        state.targetAccountFallbackColumns = if (selected != null) listOf(selected) else emptyList()
                    },
                    label = "Fallback column for account name",
                    sampleValue = getSampleValue(csvColumns, fallbackSampleRow, state.targetAccountFallbackColumns.firstOrNull()),
                    enabled = enabled,
                )

                if (state.targetAccountMode == TargetAccountMode.REGEX_MATCH) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RegexRulesEditor(
                        rules = state.regexRules,
                        onRulesChanged = { state.regexRules = it },
                        columnName = state.targetAccountColumnName,
                        columns = csvColumns,
                        rows = rows,
                        enabled = enabled,
                    )
                }
            }
            TargetAccountMode.TEMPLATE -> {
                Spacer(modifier = Modifier.height(4.dp))
                TemplateAccountMappingEditor(
                    columnName = state.targetTemplateColumnName,
                    onColumnChanged = { state.targetTemplateColumnName = it },
                    prefix = state.targetTemplatePrefix,
                    onPrefixChanged = { state.targetTemplatePrefix = it },
                    suffix = state.targetTemplateSuffix,
                    onSuffixChanged = { state.targetTemplateSuffix = it },
                    columns = csvColumns,
                    firstRow = firstRow,
                    enabled = enabled,
                )
            }
            TargetAccountMode.CONDITIONAL -> {
                Spacer(modifier = Modifier.height(4.dp))
                ConditionalAccountMappingEditor(
                    conditions = state.targetConditions,
                    onConditionsChanged = { state.targetConditions = it },
                    whenTrue = state.targetWhenTrue,
                    onWhenTrueChanged = { state.targetWhenTrue = it },
                    whenFalse = state.targetWhenFalse,
                    onWhenFalseChanged = { state.targetWhenFalse = it },
                    columns = csvColumns,
                    rows = rows,
                    firstRow = firstRow,
                    enabled = enabled,
                )
            }
        }
    }
}
