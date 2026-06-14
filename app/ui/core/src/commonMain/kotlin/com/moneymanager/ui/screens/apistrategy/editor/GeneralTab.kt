package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiAuthType

@Composable
internal fun GeneralTab(
    state: ApiStrategyEditorState,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFieldRow(
            label = "Strategy name",
            value = state.name,
            onValueChange = { state.name = it },
            enabled = enabled,
            isError = state.name.isBlank(),
        )
        TextFieldRow(
            label = "Base URL",
            value = state.baseUrl,
            onValueChange = { state.baseUrl = it },
            enabled = enabled,
            placeholder = "https://api.example.com",
            isError = state.baseUrl.isBlank(),
        )
        EnumDropdown(
            label = "Auth type",
            options = ApiAuthType.entries,
            selected = state.authType,
            onSelect = { state.authType = it },
            optionLabel = { it.name },
            enabled = enabled,
        )
        TextFieldRow(
            label = "Account name prefix",
            value = state.accountNamePrefix,
            onValueChange = { state.accountNamePrefix = it },
            enabled = enabled,
            placeholder = "e.g. Monzo: ",
        )
        TextFieldRow(
            label = "Counterparty account prefix",
            value = state.counterpartyPrefix,
            onValueChange = { state.counterpartyPrefix = it },
            enabled = enabled,
            placeholder = "e.g. Monzo Counterparty: ",
        )
        TextFieldRow(
            label = "Person external-id attribute (optional)",
            value = state.personExternalIdAttribute,
            onValueChange = { state.personExternalIdAttribute = it },
            enabled = enabled,
            placeholder = "e.g. monzo-external-id",
        )
    }
}
