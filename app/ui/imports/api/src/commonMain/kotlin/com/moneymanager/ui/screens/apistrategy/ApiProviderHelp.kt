package com.moneymanager.ui.screens.apistrategy

import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy

/**
 * Per-provider onboarding help. A strategy carries no docs URL or instructions of its own, so the only
 * signal available is its base URL — user-defined strategies simply get no help, which is why none of this
 * is required to connect.
 */
private enum class ProviderKind { MONZO, WISE, STARLING }

private fun providerKind(strategy: ApiImportStrategy?): ProviderKind? {
    val baseUrl = strategy?.baseUrl?.lowercase() ?: return null
    return when {
        "monzo" in baseUrl -> ProviderKind.MONZO
        "wise" in baseUrl || "transferwise" in baseUrl -> ProviderKind.WISE
        "starling" in baseUrl -> ProviderKind.STARLING
        else -> null
    }
}

/** Convenience deep-link to a known provider's API-token page, derived from the strategy base URL. */
internal fun providerTokenPageUrl(strategy: ApiImportStrategy?): String? =
    when (providerKind(strategy)) {
        ProviderKind.MONZO -> "https://developers.monzo.com/"
        ProviderKind.WISE -> "https://wise.com/your-account/integrations-and-tools/api-tokens"
        ProviderKind.STARLING -> "https://developer.starlingbank.com/"
        null -> null
    }

/** Step-by-step instructions for obtaining an access token from a known provider. */
internal fun connectInstructions(strategy: ApiImportStrategy?): List<String> =
    when (providerKind(strategy)) {
        ProviderKind.MONZO ->
            listOf(
                "Open the Monzo Developer Playground in your browser.",
                "Log in with your Monzo account credentials.",
                "Monzo will send a magic link to your email or app. Approve the login.",
                "Copy the access token shown on the playground page.",
                "Paste the token below and save.",
                "In the Monzo app, approve the API access notification so transactions can be read.",
            )
        ProviderKind.WISE ->
            listOf(
                "Open the Wise API tokens page in your browser and sign in.",
                "Create a new API token (read access is sufficient) and copy it.",
                "Paste the token below and save.",
                "Statements are protected by Strong Customer Authentication: generate a signing key below and " +
                    "register its public key in Wise (Settings → API tokens → Manage public keys).",
                "Note: retrieving statements via the API is only supported for accounts based in the US, Canada, " +
                    "Australia, New Zealand, Singapore, and Malaysia.",
            )
        ProviderKind.STARLING ->
            listOf(
                "Open the Starling Developer portal in your browser and sign in with your Starling account.",
                "Create a personal access token with the account:read, transaction:read and " +
                    "account-holder-name:read scopes.",
                "Copy the token, paste it below and save.",
            )
        null -> emptyList()
    }

/** True when the strategy authenticates with an api-key + secret pair rather than a single bearer token. */
internal fun ApiImportStrategy.isSigned(): Boolean = authType == ApiAuthType.SIGNED

/**
 * The session type recorded against a new credential. The type predates strategies and is now only a coarse
 * bearer-vs-signed marker, so it is derived in exactly one place rather than re-guessed at each call site.
 */
internal fun defaultSessionTypeFor(strategy: ApiImportStrategy): ApiSessionType =
    if (strategy.isSigned()) ApiSessionType.CRYPTO_COM_EXCHANGE else ApiSessionType.MONZO
