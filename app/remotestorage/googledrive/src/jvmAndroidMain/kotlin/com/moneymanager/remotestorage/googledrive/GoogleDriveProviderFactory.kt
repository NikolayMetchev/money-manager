package com.moneymanager.remotestorage.googledrive

import com.moneymanager.localsettings.LocalSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

// One Ktor client for the whole app lifetime; the Drive provider is otherwise cheap to recreate per call.
private val sharedHttpClient by lazy { HttpClient(CIO) }

/**
 * Builds a [GoogleDriveProvider] from the [config] persisted in the database binding (the user's OAuth
 * client id/secret as JSON) plus the platform [browser] for the consent step. The refresh token is read
 * from / written to [localSettings] via [GoogleDriveAccountStore]. Shared by the JVM and Android DI
 * factories so both platforms construct the provider identically.
 */
fun googleDriveProvider(
    config: String?,
    localSettings: LocalSettings,
    browser: BrowserLauncher,
): GoogleDriveProvider {
    val credentials =
        GoogleDriveCredentials.fromConfig(
            requireNotNull(config) { "Google Drive requires its OAuth client credentials" },
        )
    return GoogleDriveProvider(
        credentials = credentials,
        accountStore = GoogleDriveAccountStore(localSettings),
        browser = browser,
        httpClient = sharedHttpClient,
    )
}
