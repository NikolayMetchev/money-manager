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
    val config = state.config
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
            value = config.baseUrl,
            onValueChange = { value -> state.updateConfig { copy(baseUrl = value) } },
            enabled = enabled,
            placeholder = "https://api.example.com",
            isError = config.baseUrl.isBlank(),
        )
        EnumDropdown(
            label = "Auth type",
            options = ApiAuthType.entries,
            selected = config.authType,
            onSelect = { value -> state.updateConfig { copy(authType = value) } },
            optionLabel = { it.name },
            enabled = enabled,
        )
        TextFieldRow(
            label = "Person external-id attribute (optional)",
            value = config.personExternalIdAttribute.orEmpty(),
            onValueChange = { value -> state.updateConfig { copy(personExternalIdAttribute = value) } },
            enabled = enabled,
            placeholder = "e.g. monzo-external-id",
        )
        TextFieldRow(
            label = "Token page URL (optional)",
            value = config.tokenPageUrl.orEmpty(),
            onValueChange = { value -> state.updateConfig { copy(tokenPageUrl = value) } },
            enabled = enabled,
            placeholder = "https://provider.example.com/developer/tokens",
        )
        StringListEditor(
            label = "Connect instructions (shown as numbered steps)",
            items = config.connectInstructions,
            onChange = { value -> state.updateConfig { copy(connectInstructions = value) } },
            enabled = enabled,
        )
        OptionalLongFieldRow(
            label = "Rate-limit delay per request (ms, optional)",
            value = config.rateLimitMillis,
            onValueChange = { value -> state.updateConfig { copy(rateLimitMillis = value) } },
            enabled = enabled,
            placeholder = "blank uses the download engine's default",
        )
        StringListEditor(
            label = "Rate-limit error substrings (case-insensitive; a match triggers backoff + retry)",
            items = config.rateLimitErrorSubstrings,
            onChange = { value -> state.updateConfig { copy(rateLimitErrorSubstrings = value) } },
            enabled = enabled,
        )
        LongFieldRow(
            label = "Rate-limit retry base backoff (ms)",
            value = config.rateLimitBackoffMillis,
            onValueChange = { value -> state.updateConfig { copy(rateLimitBackoffMillis = value) } },
            enabled = enabled,
            isError = config.rateLimitBackoffMillis <= 0,
        )
        IntFieldRow(
            label = "Max rate-limit retries",
            value = config.maxRateLimitRetries,
            onValueChange = { value -> state.updateConfig { copy(maxRateLimitRetries = value) } },
            enabled = enabled,
        )
    }
}
