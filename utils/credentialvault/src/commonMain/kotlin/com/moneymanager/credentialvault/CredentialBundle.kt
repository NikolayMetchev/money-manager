package com.moneymanager.credentialvault

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Everything the credential vault file holds. Kept outside any database so wiping or recreating a
 * database never costs the user their API tokens or Google sign-in.
 */
@Serializable
data class CredentialBundle(
    // Always written, even though it equals the default: otherwise every version would omit it and a file
    // from a newer build would decode here as the current version, defeating the version check on read.
    @EncodeDefault val version: Int = CURRENT_VERSION,
    val apiCredentials: List<StoredApiCredential> = emptyList(),
    val googleAccounts: List<StoredGoogleAccount> = emptyList(),
) {
    fun apiCredential(strategyName: String): StoredApiCredential? = apiCredentials.firstOrNull { it.strategyName == strategyName }

    /** Replaces (or adds) the credential for [credential]'s strategy, so each strategy holds at most one. */
    fun withApiCredential(credential: StoredApiCredential): CredentialBundle =
        copy(apiCredentials = apiCredentials.filterNot { it.strategyName == credential.strategyName } + credential)

    fun googleAccount(clientId: String): StoredGoogleAccount? = googleAccounts.firstOrNull { it.clientId == clientId }

    fun withGoogleAccount(account: StoredGoogleAccount): CredentialBundle =
        copy(googleAccounts = googleAccounts.filterNot { it.clientId == account.clientId } + account)

    fun withoutGoogleAccount(clientId: String): CredentialBundle =
        copy(googleAccounts = googleAccounts.filterNot { it.clientId == clientId })

    /**
     * Combines this bundle with [other] (e.g. this device's vault and the copy backed up from another
     * device), keeping every strategy and Google client either side knows. Where both hold an API
     * credential, the more recently created wins; where both hold a Google account, this side's wins, since
     * it is the sign-in this device just consented to.
     */
    fun mergedWith(other: CredentialBundle): CredentialBundle {
        val mergedApi =
            (apiCredentials + other.apiCredentials)
                .groupBy { it.strategyName }
                .values
                .map { candidates -> candidates.maxBy { it.createdAtEpochMillis } }
        val mergedGoogle = googleAccounts + other.googleAccounts.filter { googleAccount(it.clientId) == null }
        return copy(apiCredentials = mergedApi, googleAccounts = mergedGoogle)
    }

    override fun toString(): String = "CredentialBundle(version=$version, apiCredentials=$apiCredentials, googleAccounts=$googleAccounts)"

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * The secrets for one API strategy. Keyed by strategy **name**, not id: installing a strategy from the
 * catalog gives it a fresh random id in every database, while its name is unique and stable, so a
 * recreated database finds its credentials again once the strategy is reinstalled.
 */
@Serializable
data class StoredApiCredential(
    val strategyName: String,
    /** Bearer token, or the api key for signed exchange strategies. */
    val token: String,
    val createdAtEpochMillis: Long,
    /** HMAC secret for signed exchange strategies; null for bearer/SCA strategies. */
    val apiSecret: String? = null,
    /** PEM-encoded RSA key pair for request signing (e.g. Wise SCA); null when not configured. */
    val privateKeyPem: String? = null,
    val publicKeyPem: String? = null,
) {
    override fun toString(): String =
        "StoredApiCredential(strategyName=$strategyName, token=<redacted>, createdAtEpochMillis=$createdAtEpochMillis, " +
            "apiSecret=${redactedOrNull(apiSecret)}, privateKeyPem=${redactedOrNull(privateKeyPem)}, " +
            "publicKeyPem=${redactedOrNull(publicKeyPem)})"
}

/** The long-lived Google OAuth grant for one OAuth client (one Google account per client). */
@Serializable
data class StoredGoogleAccount(
    val clientId: String,
    val refreshToken: String,
    val grantedScopes: Set<String> = emptySet(),
) {
    override fun toString(): String = "StoredGoogleAccount(clientId=$clientId, refreshToken=<redacted>, grantedScopes=$grantedScopes)"
}

private fun redactedOrNull(value: String?): String = if (value != null) "<redacted>" else "null"
