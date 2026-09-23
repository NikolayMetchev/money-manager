package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry

/** Edits [ApiAccountMappings] in place inside the strategy config. */
private fun ApiStrategyEditorState.updateAccountMappings(block: ApiAccountMappings.() -> ApiAccountMappings) =
    updateConfig { copy(accountMappings = accountMappings.block()) }

@Composable
internal fun AccountMappingsTab(
    state: ApiStrategyEditorState,
    accountJsonPaths: List<JsonPathEntry>,
    accountSampleLoaded: Boolean,
    onRequestPick: PathPicker,
    enabled: Boolean,
) {
    val mappings = state.config.accountMappings
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionDataStatus(paths = accountJsonPaths, loaded = accountSampleLoaded)

        PathFieldRow(
            "Account ID field",
            mappings.idField,
            { value -> state.updateAccountMappings { copy(idField = value) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Description field",
            mappings.descriptionField,
            { value -> state.updateAccountMappings { copy(descriptionField = value) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        TextFieldRow(
            label = "Static account name (optional)",
            value = mappings.staticAccountName.orEmpty(),
            onValueChange = { value -> state.updateAccountMappings { copy(staticAccountName = value.ifBlank { null }) } },
            enabled = enabled,
            placeholder = "Overrides the description field above (e.g. \"Monzo\") — \"Joint\" is appended for a joint account",
        )
        PathFieldRow(
            "Owner name field (optional)",
            mappings.ownerNameField.orEmpty(),
            { value -> state.updateAccountMappings { copy(ownerNameField = value.ifBlank { null }) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Owners array field (optional)",
            mappings.ownersArrayField.orEmpty(),
            { value -> state.updateAccountMappings { copy(ownersArrayField = value.ifBlank { null }) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Owner user-id field",
            mappings.ownerUserIdField,
            { value -> state.updateAccountMappings { copy(ownerUserIdField = value) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Owner name fallback field",
            mappings.ownerNameFallbackField,
            { value -> state.updateAccountMappings { copy(ownerNameFallbackField = value) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Sort code field",
            mappings.sortCodeField,
            { value -> state.updateAccountMappings { copy(sortCodeField = value) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Account number field",
            mappings.accountNumberField,
            { value -> state.updateAccountMappings { copy(accountNumberField = value) } },
            accountJsonPaths,
            onRequestPick,
            enabled,
        )
        PathFieldRow(
            "Currency field (optional)",
            mappings.currencyField.orEmpty(),
            { value -> state.updateAccountMappings { copy(currencyField = value.ifBlank { null }) } },
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
