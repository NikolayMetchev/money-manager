package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import com.moneymanager.domain.model.serialization.SortedStringToStringMapSerializer
import kotlinx.serialization.Serializable

/**
 * Strong Customer Authentication (request-signing) parameters for a provider that protects some
 * endpoints behind a challenge-response signature (e.g. Wise balance statements). When a request is
 * rejected with [triggerStatus] and returns a one-time token in [challengeHeader], the importer signs
 * the token with the credential's private key and retries with the signature in [signatureHeader].
 *
 * @property challengeHeader Response header carrying the one-time token (e.g. "x-2fa-approval")
 * @property signatureHeader Request header for the Base64 signature on retry (e.g. "X-Signature")
 * @property triggerStatus HTTP status that signals a signing challenge (e.g. 403)
 * @property statementCountries ISO 3166-1 alpha-2 country codes whose accounts can retrieve
 *                             statements/transactions via the API. Empty means no restriction; when
 *                             non-empty the UI disables transaction download for other locales (Wise
 *                             only supports statements for US/CA/AU/NZ/SG/MY).
 */
@Serializable
data class ApiSigningConfig(
    val challengeHeader: String = "x-2fa-approval",
    val signatureHeader: String = "X-Signature",
    val triggerStatus: Int = 403,
    @Serializable(with = SortedStringSetSerializer::class)
    val statementCountries: Set<String> = emptySet(),
)

/**
 * Trades a long-lived client id + secret for a short-lived bearer token before a download (the OAuth2
 * client-credentials grant, e.g. PayPal). The credential's token is the client id and its api secret the
 * client secret; they are sent as HTTP Basic auth (RFC 6749's default client authentication) with
 * [formParams] as an `application/x-www-form-urlencoded` POST to [path], and [accessTokenField] of the JSON
 * response is used as the bearer token for every request of that download.
 */
@Serializable
data class ApiTokenExchange(
    /** Token endpoint, relative to [ApiStrategyConfig.baseUrl]. */
    val path: String = "/v1/oauth2/token",
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val formParams: Map<String, String> = mapOf("grant_type" to "client_credentials"),
    /** Dot-path of the access token in the token response. */
    val accessTokenField: String = "access_token",
) {
    fun isValidForSave(): Boolean = path.isNotBlank() && accessTokenField.isNotBlank()
}
