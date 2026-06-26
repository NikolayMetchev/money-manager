package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig

@Composable
internal fun AdvancedTab(
    state: ApiStrategyEditorState,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Request signing (Strong Customer Authentication)")
        Text(
            text = "For providers that protect some endpoints behind a challenge-response signature (e.g. Wise).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = "Enable request signing",
            checked = state.signing != null,
            onCheckedChange = { on -> state.signing = if (on) ApiSigningConfig() else null },
            enabled = enabled,
        )
        state.signing?.let { signing ->
            TextFieldRow("Challenge header", signing.challengeHeader, { state.signing = signing.copy(challengeHeader = it) }, enabled)
            TextFieldRow("Signature header", signing.signatureHeader, { state.signing = signing.copy(signatureHeader = it) }, enabled)
            IntFieldRow("Trigger status", signing.triggerStatus, { state.signing = signing.copy(triggerStatus = it) }, enabled)
            StringSetEditor(
                label = "Statement countries (ISO 3166-1 alpha-2)",
                values = signing.statementCountries,
                onChange = { state.signing = signing.copy(statementCountries = it) },
                enabled = enabled,
            )
        }
    }
}
