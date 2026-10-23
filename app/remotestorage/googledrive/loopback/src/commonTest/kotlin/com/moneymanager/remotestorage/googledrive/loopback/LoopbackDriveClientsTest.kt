package com.moneymanager.remotestorage.googledrive.loopback

import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.remotestorage.googledrive.GoogleDriveCredentials
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LoopbackDriveClientsTest {
    private val config = GoogleDriveCredentials(clientId = "client-id", clientSecret = "client-secret").toConfig()
    private val browser = BrowserLauncher { error("nothing signs in here") }

    @Test
    fun `a build without OAuth credentials has no Drive provider`() {
        assertFailsWith<IllegalArgumentException> { googleDriveProvider(null, inMemoryCredentialVault(), browser) }
        assertFailsWith<IllegalArgumentException> { googleDriveTokenSource(null, inMemoryCredentialVault(), browser) }
    }

    @Test
    fun `a provider starts signed out until the vault holds a refresh token`() =
        runTest {
            val vault = unlockedCredentialVault()

            assertFalse(googleDriveProvider(config, vault, browser, subfolder = "Strategies").isSignedIn())
            // A second provider for the same OAuth client shares its Drive client.
            assertFalse(googleDriveProvider(config, vault, browser).isSignedIn())
            assertFalse(googleDriveTokenSource(config, vault, browser).isSignedIn())
        }
}
