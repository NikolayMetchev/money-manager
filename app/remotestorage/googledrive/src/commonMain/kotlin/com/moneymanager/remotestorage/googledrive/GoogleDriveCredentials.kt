package com.moneymanager.remotestorage.googledrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Provider id shared with the DI factory; persisted in the database binding to re-bind on launch. */
const val GOOGLE_DRIVE_PROVIDER_ID = "google-drive"

/** The Drive folder the app keeps its database archives in (created on first upload). */
const val GOOGLE_DRIVE_FOLDER_NAME = "Money Manager"

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * A Google OAuth client ([clientId] + [clientSecret]) for the desktop loopback flow. On JVM these are
 * the app's shipped default credentials (build-time injected via `GoogleOAuthDefaults`); the value can
 * also be carried in a database binding's `providerConfig` for provider reconstruction on later
 * launches. The long-lived refresh token is NOT stored here — the provider keeps it in
 * `GoogleDriveAccountStore`.
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
    }
}
