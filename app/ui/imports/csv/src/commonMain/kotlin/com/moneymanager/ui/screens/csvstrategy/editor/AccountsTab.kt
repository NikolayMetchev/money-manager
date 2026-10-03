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
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.components.AccountPicker

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
    existingAttributeTypes: List<AttributeType>,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
) {
    val attributeTypeNames = existingAttributeTypes.map { it.name }
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
                selected = state.sourceAccountMode == SourceAccountMode.RULES,
                onClick = {
                    state.sourceAccountMode = SourceAccountMode.RULES
                    if (state.sourceRules.isEmpty()) state.sourceRules = listOf(defaultAccountRule(csvColumns.firstOrNull()?.originalName))
                },
                enabled = enabled,
            )
            Text("From the row (rules)")
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
            SourceAccountMode.RULES ->
                AccountRulesEditor(
                    rules = state.sourceRules,
                    onRulesChanged = { state.sourceRules = it },
                    columns = csvColumns,
                    rows = rows,
                    firstRow = firstRow,
                    existingAttributeTypeNames = attributeTypeNames,
                    enabled = enabled,
                )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Target Account", style = MaterialTheme.typography.titleSmall)
        Text(
            "How the target (counterparty) account is named for each row",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccountRulesEditor(
            rules = state.targetRules,
            onRulesChanged = { state.targetRules = it },
            columns = csvColumns,
            rows = rows,
            firstRow = firstRow,
            existingAttributeTypeNames = attributeTypeNames,
            enabled = enabled,
        )
    }
}
