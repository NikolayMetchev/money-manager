package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
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

        HorizontalDivider()
        SectionHeader("Proactive request signing (exchanges)")
        Text(
            text =
                "A per-request HMAC signature computed for every call (Crypto.com/Binance/Kraken). Used " +
                    "when the authentication type is SIGNED.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RequestSigningEditor(
            config = state.requestSigning,
            onChange = { state.requestSigning = it },
            enabled = enabled,
        )

        HorizontalDivider()
        SectionHeader("Internal-transfer reconciliation")
        Text(
            text =
                "Collapse this account's transfers that another owned account records at its own end " +
                    "(e.g. App→Exchange) into one internal transfer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InternalTransferReconcileEditor(
            config = state.internalTransferReconcile,
            onChange = { state.internalTransferReconcile = it },
            enabled = enabled,
        )

        HorizontalDivider()
        SectionHeader("Asset aliases")
        Text(
            text =
                "Normalizes a provider's raw asset/currency code to its canonical form before lookup " +
                    "(e.g. Kraken's legacy \"XXBT\" -> \"BTC\").",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StringMapEditor(
            label = "Aliases (raw code -> canonical code)",
            entries = state.assetAliases,
            onChange = { state.assetAliases = it },
            keyLabel = "Raw code",
            valueLabel = "Canonical code",
            enabled = enabled,
        )
        Text(
            text =
                "Suffixes stripped from a raw code before the alias/currency lookup above (e.g. Kraken's " +
                    "Earn holding \"XETH.F\" -> \"XETH\").",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StringSetEditor(
            label = "Suffixes to strip",
            values = state.assetSuffixesToStrip,
            onChange = { state.assetSuffixesToStrip = it },
            enabled = enabled,
        )

        HorizontalDivider()
        SectionHeader("Minor-unit divisor overrides")
        Text(
            text =
                "Only used when a transaction/fee amount is in \"integer minor units\" format (e.g. " +
                    "Monzo's pence). Overrides the ISO 4217 standard divisor for a currency this " +
                    "provider reports differently (e.g. 1000 instead of the standard 100).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StringLongMapEditor(
            label = "Overrides (currency code -> divisor)",
            entries = state.minorUnitDivisorOverrides,
            onChange = { state.minorUnitDivisorOverrides = it },
            keyLabel = "Currency code",
            valueLabel = "Divisor",
            enabled = enabled,
        )
    }
}
