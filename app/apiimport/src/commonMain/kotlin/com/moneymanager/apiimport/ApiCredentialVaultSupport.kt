package com.moneymanager.apiimport

import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.rest.generateScaKeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The installed [strategies] that have secrets in [bundle] but no connection row among [credentialRows].
 * Giving each a row is what makes a recreated database pick its APIs straight back up: reinstall the
 * strategy, unlock the vault, and it's connected again with no token re-entry.
 */
fun strategiesMissingConnections(
    strategies: List<ApiImportStrategy>,
    credentialRows: List<ApiCredential>,
    bundle: CredentialBundle,
): Set<ApiImportStrategyId> {
    val connectedStrategyIds = credentialRows.mapNotNull { it.strategyId }.toSet()
    return strategies
        .filter { it.id !in connectedStrategyIds && bundle.apiCredential(it.name) != null }
        .map { it.id }
        .toSet()
}

/**
 * Generates a fresh SCA signing key pair and stores it with [strategyName]'s credentials. Returns null on
 * success, or a message for the signing-key section when the vault stays locked or can't be written.
 */
suspend fun CredentialVault.storeNewSigningKey(strategyName: String): String? =
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
class MissingApiSecretsException(
    message: String,
) : Exception(message)

/**
 * [strategy]'s secrets from the vault, prompting to unlock it first. Throws [MissingApiSecretsException]
 * when the user declines to unlock or nothing is saved for the strategy.
 */
suspend fun CredentialVault.downloadSecretsFor(strategy: ApiImportStrategy): StoredApiCredential {
    val bundle =
        try {
            requireUnlocked("Download ${strategy.name}")
        } catch (_: CredentialVaultLockedException) {
            throw MissingApiSecretsException("Your credentials are locked; unlock them to download.")
        }
    return bundle.apiCredential(strategy.name)
        ?: throw MissingApiSecretsException("No ${strategy.name} credentials are saved in your credential file; reconnect it.")
}
