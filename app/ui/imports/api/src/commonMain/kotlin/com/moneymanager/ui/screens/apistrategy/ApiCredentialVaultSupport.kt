package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.apiimport.strategiesMissingConnections
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalCredentialVault
import kotlinx.coroutines.launch

/**
 * Gives every installed strategy that has secrets in the unlocked credential vault a connection row in
 * the database (see [ApiImportService.ensureConnections]), whenever the vault unlocks or the strategies
 * change. This is what makes a recreated database pick its APIs straight back up.
 */
@Composable
internal fun EnsureApiConnectionRows(
    apiImportService: ApiImportService,
    strategies: List<ApiImportStrategy>,
    credentialRows: List<ApiCredential>,
    onCreated: () -> Unit = {},
) {
    val vault = LocalCredentialVault.current
    val vaultState by vault.state.collectAsState()
    val bundle = (vaultState as? VaultState.Unlocked)?.bundle ?: return
    val missing = strategiesMissingConnections(strategies, credentialRows, bundle)
    LaunchedEffect(missing) {
        if (missing.isNotEmpty() && apiImportService.ensureConnections() > 0) onCreated()
    }
}

/** Shown where secrets are needed but the vault is locked; the button raises the unlock prompt. */
@Composable
internal fun CredentialsLockedBanner(reason: String) {
    val vault = LocalCredentialVault.current
    val scope = rememberSchemaAwareCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Your API credentials are locked in your credential file.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            vault.requireUnlocked(reason)
                        } catch (_: CredentialVaultLockedException) {
                            // The user dismissed the prompt; the banner stays as the reminder.
                        }
                    }
                },
            ) { Text("Unlock credentials") }
        }
    }
}
