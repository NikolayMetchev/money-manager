package com.moneymanager.credentialvault

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Everything the credential vault file holds. Kept outside any database so wiping or recreating a
 * database never costs the user their API tokens or Google sign-in.
 *
 * A deleted credential leaves a [DeletedCredential] marker behind, so merging with a backup that still holds
 * it ([mergedWith]) doesn't bring it back.
 */
@Serializable
data class CredentialBundle(
    // Always written, even though it equals the default: otherwise every version would omit it and a file
    // from a newer build would decode here as the current version, defeating the version check on read.
    @EncodeDefault val version: Int = CURRENT_VERSION,
    val apiCredentials: List<StoredApiCredential> = emptyList(),
    val googleAccounts: List<StoredGoogleAccount> = emptyList(),
    /** Deletion markers for API credentials, keyed by strategy name. */
    val deletedApiCredentials: List<DeletedCredential> = emptyList(),
    /** Deletion markers for Google accounts, keyed by OAuth client id. */
    val deletedGoogleAccounts: List<DeletedCredential> = emptyList(),
) {
    fun apiCredential(strategyName: String): StoredApiCredential? = apiCredentials.firstOrNull { it.strategyName == strategyName }

    /** Replaces (or adds) the credential for [credential]'s strategy, so each strategy holds at most one. */
    fun withApiCredential(credential: StoredApiCredential): CredentialBundle =
        copy(
            apiCredentials = apiCredentials.filterNot { it.strategyName == credential.strategyName } + credential,
            deletedApiCredentials = deletedApiCredentials.filterNot { it.key == credential.strategyName },
        )

    /** Removes [strategyName]'s credential, leaving a marker so a merge with an older copy doesn't restore it. */
    fun withoutApiCredential(
        strategyName: String,
        deletedAtEpochMillis: Long,
    ): CredentialBundle =
        copy(
            apiCredentials = apiCredentials.filterNot { it.strategyName == strategyName },
            deletedApiCredentials =
                deletedApiCredentials.filterNot { it.key == strategyName } + DeletedCredential(strategyName, deletedAtEpochMillis),
        )

    fun googleAccount(clientId: String): StoredGoogleAccount? = googleAccounts.firstOrNull { it.clientId == clientId }

    fun withGoogleAccount(account: StoredGoogleAccount): CredentialBundle =
        copy(
            googleAccounts = googleAccounts.filterNot { it.clientId == account.clientId } + account,
            deletedGoogleAccounts = deletedGoogleAccounts.filterNot { it.key == account.clientId },
        )

    /** Removes [clientId]'s Google account, leaving a marker so a merge with an older copy doesn't restore it. */
    fun withoutGoogleAccount(
        clientId: String,
        deletedAtEpochMillis: Long,
    ): CredentialBundle =
        copy(
            googleAccounts = googleAccounts.filterNot { it.clientId == clientId },
            deletedGoogleAccounts =
                deletedGoogleAccounts.filterNot { it.key == clientId } + DeletedCredential(clientId, deletedAtEpochMillis),
        )

    /**
     * Combines this bundle with [other] (e.g. this device's vault and the copy backed up from another
     * device), keeping every strategy and Google client either side knows. Where both hold an API
     * credential, the more recently created wins; where both hold a Google account, this side's wins, since
     * it is the sign-in this device just consented to. A deletion on either side wins over a credential that
     * was saved no later than it (a Google account signed in before deletion markers existed counts as saved
     * at 0), and one saved after it wins over the deletion.
     */
    fun mergedWith(other: CredentialBundle): CredentialBundle {
        val apiCandidates =
            (apiCredentials + other.apiCredentials)
                .groupBy { it.strategyName }
                .values
                .map { candidates -> candidates.maxBy { it.createdAtEpochMillis } }
        val googleCandidates = googleAccounts + other.googleAccounts.filter { googleAccount(it.clientId) == null }
        val (mergedApi, mergedApiDeletions) =
            resolveDeletions(apiCandidates, deletedApiCredentials + other.deletedApiCredentials, { it.strategyName }) {
                it.createdAtEpochMillis
            }
        val (mergedGoogle, mergedGoogleDeletions) =
            resolveDeletions(googleCandidates, deletedGoogleAccounts + other.deletedGoogleAccounts, { it.clientId }) {
                it.signedInAtEpochMillis
            }
        return copy(
            apiCredentials = mergedApi,
            googleAccounts = mergedGoogle,
            deletedApiCredentials = mergedApiDeletions,
            deletedGoogleAccounts = mergedGoogleDeletions,
        )
    }

    override fun toString(): String =
        "CredentialBundle(version=$version, apiCredentials=$apiCredentials, googleAccounts=$googleAccounts, " +
            "deletedApiCredentials=$deletedApiCredentials, deletedGoogleAccounts=$deletedGoogleAccounts)"

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
    /** When this device signed in, so a merge can tell it from a later sign-out elsewhere; 0 for older files. */
    val signedInAtEpochMillis: Long = 0,
) {
    override fun toString(): String =
        "StoredGoogleAccount(clientId=$clientId, refreshToken=<redacted>, grantedScopes=$grantedScopes, " +
            "signedInAtEpochMillis=$signedInAtEpochMillis)"
}

/** Marks the credential under [key] (a strategy name or OAuth client id) as deleted at [deletedAtEpochMillis]. */
@Serializable
data class DeletedCredential(
    val key: String,
    val deletedAtEpochMillis: Long,
)

/**
 * Settles each key between its surviving [candidates] and [deletions]: the newest deletion of a key wins over a
 * candidate saved at or before it. Returns the kept candidates and the deletions still in force (one per key).
 */
private fun <T> resolveDeletions(
    candidates: List<T>,
    deletions: List<DeletedCredential>,
    keyOf: (T) -> String,
    savedAt: (T) -> Long,
): Pair<List<T>, List<DeletedCredential>> {
    val newestDeletion =
        deletions
            .groupBy { it.key }
            .mapValues { (_, markers) -> markers.maxBy { it.deletedAtEpochMillis } }
    val kept =
        candidates.filter { candidate ->
            newestDeletion[keyOf(candidate)]?.let { savedAt(candidate) > it.deletedAtEpochMillis }
                ?: true
        }
    val keptKeys = kept.map(keyOf).toSet()
    return kept to newestDeletion.values.filter { it.key !in keptKeys }
}

private fun redactedOrNull(value: String?): String = if (value != null) "<redacted>" else "null"
