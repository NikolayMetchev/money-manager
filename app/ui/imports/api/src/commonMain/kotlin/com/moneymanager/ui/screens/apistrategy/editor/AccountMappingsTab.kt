package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

@Composable
internal fun AccountMappingsTab(
    state: ApiStrategyEditorState,
    accountJsonPaths: List<JsonPathEntry>,
    accountSampleLoaded: Boolean,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val mappings = state.accountMappings
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionDataStatus(paths = accountJsonPaths, loaded = accountSampleLoaded)

        PathFieldRow(
            "Account ID field",
            mappings.idField,
            { state.accountMappings = mappings.copy(idField = it) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Description field",
            mappings.descriptionField,
            { state.accountMappings = mappings.copy(descriptionField = it) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        TextFieldRow(
            label = "Static account name (optional)",
            value = mappings.staticAccountName.orEmpty(),
            onValueChange = { state.accountMappings = mappings.copy(staticAccountName = it.ifBlank { null }) },
            enabled = enabled,
            placeholder = "Overrides the description field above (e.g. \"Monzo\") — \"Joint\" is appended for a joint account",
        )
        PathFieldRow(
            "Owner name field (optional)",
            mappings.ownerNameField.orEmpty(),
            { state.accountMappings = mappings.copy(ownerNameField = it.ifBlank { null }) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Owners array field (optional)",
            mappings.ownersArrayField.orEmpty(),
            { state.accountMappings = mappings.copy(ownersArrayField = it.ifBlank { null }) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Owner user-id field",
            mappings.ownerUserIdField,
            { state.accountMappings = mappings.copy(ownerUserIdField = it) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Owner name fallback field",
            mappings.ownerNameFallbackField,
            { state.accountMappings = mappings.copy(ownerNameFallbackField = it) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Sort code field",
            mappings.sortCodeField,
            { state.accountMappings = mappings.copy(sortCodeField = it) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Account number field",
            mappings.accountNumberField,
            { state.accountMappings = mappings.copy(accountNumberField = it) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Currency field (optional)",
            mappings.currencyField.orEmpty(),
            { state.accountMappings = mappings.copy(currencyField = it.ifBlank { null }) },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        CustomFieldsSection(
            fields = state.accountCustomFields,
            onFieldsChange = { state.accountCustomFields = it },
            paths = accountJsonPaths,
            onRequestPick = onRequestPick,
            enabled = enabled,
        )
    }
}
