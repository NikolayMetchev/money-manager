package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.ui.screens.csvstrategy.AccountMappingsSection

/**
 * Advanced tab: attributes, row preprocessing rules, companion transaction rules, and the
 * edit-mode account mappings list.
 */
@Composable
internal fun AdvancedTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
    existingAttributeTypes: List<AttributeType>,
    isEditMode: Boolean,
    accountMappings: List<CsvAccountMapping>,
    accounts: List<Account>,
    onEditAccountMapping: (CsvAccountMapping) -> Unit,
    onDeleteAccountMapping: (CsvAccountMapping) -> Unit,
    onAddAccountMapping: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Attributes (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Select columns to store as attributes (metadata)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        val attributeColumns =
            attributeCandidateColumns(
                csvColumns = csvColumns,
                primaryFieldColumnNames =
                    setOf(
                        state.dateColumnName,
                        state.timeColumnName,
                        state.descriptionColumnName,
                        state.amountColumnName,
                        state.targetAccountColumnName,
                        state.currencyColumnName,
                        state.timezoneColumnName,
                    ),
            )

        if (attributeColumns.isEmpty()) {
            Text(
                "No columns available for attributes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AttributeMappingsEditor(
                columns = attributeColumns,
                mappings = state.attributeMappings,
                onMappingsChanged = { state.attributeMappings = it },
                existingAttributeTypes = existingAttributeTypes,
                enabled = enabled,
                firstRow = firstRow,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Row Preprocessing Rules (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Swap column values and/or flip source/target accounts when conditions match",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        RowPreprocessingRulesEditor(
            rules = state.rowPreprocessingRules,
            onRulesChanged = { state.rowPreprocessingRules = it },
            columns = csvColumns,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Companion Transaction Rules (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Flag imported transfers that imply a manually entered companion transaction",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CompanionTransactionRulesEditor(
            rules = state.companionTransactionRules,
            onRulesChanged = { state.companionTransactionRules = it },
            enabled = enabled,
        )

        if (isEditMode) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Account Mappings", style = MaterialTheme.typography.titleSmall)
            Text(
                "CSV values mapped to existing accounts (auto-created during import)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            AccountMappingsSection(
                mappings = accountMappings,
                accounts = accounts,
                enabled = enabled,
                onEditMapping = onEditAccountMapping,
                onDeleteMapping = onDeleteAccountMapping,
                onAddMapping = onAddAccountMapping,
            )
        }
    }
}
