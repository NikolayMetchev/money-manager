package com.moneymanager.remotestorage.googledrive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Provider id shared with the DI factory; persisted in the database binding to re-bind on launch. */
const val GOOGLE_DRIVE_PROVIDER_ID = "google-drive"

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * The user's own Google OAuth client (bring-your-own-credentials). Holds just the [clientId] and
 * [clientSecret] read from the `credentials.json` they download from Google Cloud Console for a
 * "Desktop app" OAuth client. This is what the setup wizard collects, and it is serialized into the
 * database binding's `providerConfig` so the provider can be reconstructed on later launches. The
 * long-lived refresh token is NOT stored here — the provider keeps it in [GoogleDriveAccountStore].
 */
@Serializable
data class GoogleDriveCredentials(
    val clientId: String,
    val clientSecret: String,
) {
    /** Serializes to the compact string stored in the binding's `providerConfig`. */
    fun toConfig(): String = lenientJson.encodeToString(this)

    companion object {
        /** Parses the compact `providerConfig` string back into credentials. */
        fun fromConfig(config: String): GoogleDriveCredentials = lenientJson.decodeFromString(config)

        /**
         * Extracts the client id/secret from a pasted Google `credentials.json` blob (either the
         * `installed` or `web` client shape), or returns null if it isn't a recognizable client file.
         */
        fun parseCredentialsJson(blob: String): GoogleDriveCredentials? =
            runCatching {
                val file = lenientJson.decodeFromString<GoogleClientSecretsFile>(blob)
                val client = file.installed ?: file.web ?: return null
                if (client.clientId.isBlank() || client.clientSecret.isBlank()) {
                    null
                } else {
                    GoogleDriveCredentials(client.clientId, client.clientSecret)
                }
            }.getOrNull()
    }
}

@Serializable
private data class GoogleClientSecretsFile(
    val installed: GoogleClientSecrets? = null,
    val web: GoogleClientSecrets? = null,
)

@Serializable
private data class GoogleClientSecrets(
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_secret") val clientSecret: String = "",
)
