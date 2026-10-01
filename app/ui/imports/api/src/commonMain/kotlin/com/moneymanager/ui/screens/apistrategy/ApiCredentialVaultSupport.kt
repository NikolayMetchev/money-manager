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
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.importengineapi.ensureApiCredentials
import com.moneymanager.ui.api.sca.generateScaKeyPair
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val logger = logging()

/**
 * Gives every installed strategy that has secrets in the unlocked credential vault a connection row in
 * the database. This is what makes a recreated database pick its APIs straight back up: reinstall the
 * strategy, unlock the vault, and it's connected again with no token re-entry.
 */
@Composable
internal fun EnsureApiConnectionRows(
    strategies: List<ApiImportStrategy>,
    credentialRows: List<ApiCredential>,
    onCreated: () -> Unit = {},
) {
    val vault = LocalCredentialVault.current
    val importEngine = LocalImportEngine.current
    val vaultState by vault.state.collectAsState()
    val bundle = (vaultState as? VaultState.Unlocked)?.bundle ?: return
    val connectedStrategyIds = credentialRows.mapNotNull { it.strategyId }.toSet()
    val missing =
        strategies
            .filter { it.id !in connectedStrategyIds && bundle.apiCredential(it.name) != null }
            .map { it.id }
            .toSet()
    LaunchedEffect(missing) {
        if (missing.isEmpty()) return@LaunchedEffect
        // Best effort: editing may be locked (a cloud-backed database whose remote copy is ahead), and this
        // runs unprompted whenever the screen opens. The rows stay missing, so a later visit retries.
        try {
            importEngine.ensureApiCredentials(missing, Clock.System.now())
            onCreated()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            logger.error(expected) { "Couldn't create API connection rows: ${expected.message}" }
        }
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

/**
 * Generates a fresh SCA signing key pair and stores it with [strategyName]'s credentials. Returns null on
 * success, or a message for the signing-key section when the vault stays locked or can't be written.
 */
internal suspend fun CredentialVault.storeNewSigningKey(strategyName: String): String? =
    try {
        val keyPair = withContext(Dispatchers.Default) { generateScaKeyPair() }
        update("Save the $strategyName signing key") { bundle ->
            val existing = bundle.apiCredential(strategyName) ?: return@update bundle
            bundle.withApiCredential(existing.copy(privateKeyPem = keyPair.privateKeyPem, publicKeyPem = keyPair.publicKeyPem))
        }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CredentialVaultLockedException) {
        "Unlock your credential file to save the signing key."
    } catch (expected: Exception) {
        "Couldn't save the signing key: ${expected.message ?: expected::class.simpleName}"
    }

/** Why a download can't get the secrets it needs, worded for the user. */
internal class MissingApiSecretsException(
    message: String,
) : Exception(message)

/**
 * [strategy]'s secrets from the vault, prompting to unlock it first. Throws [MissingApiSecretsException]
 * when the user declines to unlock or nothing is saved for the strategy.
 */
internal suspend fun CredentialVault.downloadSecretsFor(strategy: ApiImportStrategy): StoredApiCredential {
    val bundle =
        try {
            requireUnlocked("Download ${strategy.name}")
        } catch (_: CredentialVaultLockedException) {
            throw MissingApiSecretsException("Your credentials are locked; unlock them to download.")
        }
    return bundle.apiCredential(strategy.name)
        ?: throw MissingApiSecretsException("No ${strategy.name} credentials are saved in your credential file; reconnect it.")
}
