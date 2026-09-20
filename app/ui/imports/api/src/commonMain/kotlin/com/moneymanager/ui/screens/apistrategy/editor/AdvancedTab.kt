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
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository

/** Edits the challenge-response [ApiSigningConfig] in place; a no-op while signing is disabled. */
private fun ApiStrategyEditorState.updateSigning(block: ApiSigningConfig.() -> ApiSigningConfig) =
    updateConfig { copy(signing = signing?.block()) }

@Composable
internal fun AdvancedTab(
    state: ApiStrategyEditorState,
    enabled: Boolean,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
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
            checked = state.config.signing != null,
            onCheckedChange = { on -> state.updateConfig { copy(signing = if (on) ApiSigningConfig() else null) } },
            enabled = enabled,
        )
        state.config.signing?.let { signing ->
            TextFieldRow("Challenge header", signing.challengeHeader, { v -> state.updateSigning { copy(challengeHeader = v) } }, enabled)
            TextFieldRow("Signature header", signing.signatureHeader, { v -> state.updateSigning { copy(signatureHeader = v) } }, enabled)
            IntFieldRow("Trigger status", signing.triggerStatus, { v -> state.updateSigning { copy(triggerStatus = v) } }, enabled)
            StringSetEditor(
                label = "Statement countries (ISO 3166-1 alpha-2)",
                values = signing.statementCountries,
                onChange = { v -> state.updateSigning { copy(statementCountries = v) } },
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
            config = state.config.requestSigning,
            onChange = { v -> state.updateConfig { copy(requestSigning = v) } },
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
            config = state.config.internalTransferReconcile,
            onChange = { v -> state.updateConfig { copy(internalTransferReconcile = v) } },
            enabled = enabled,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            personRepository = personRepository,
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
            entries = state.config.assetAliases,
            onChange = { v -> state.updateConfig { copy(assetAliases = v) } },
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
            values = state.config.assetSuffixesToStrip,
            onChange = { v -> state.updateConfig { copy(assetSuffixesToStrip = v) } },
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
            entries = state.config.minorUnitDivisorOverrides,
            onChange = { v -> state.updateConfig { copy(minorUnitDivisorOverrides = v) } },
            keyLabel = "Currency code",
            valueLabel = "Divisor",
            enabled = enabled,
        )
    }
}
