package com.moneymanager.remotestorage.googledrive

import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleDriveAccountStoreTest {
    private val clientId = "client"

    @Test
    fun `a cached access token is only served while the vault is unlocked`() =
        runTest {
            val vault = unlockedCredentialVault()
            val store = GoogleDriveAccountStore(vault)
            store.saveSignIn(clientId, "refresh-1", setOf(DRIVE_FILE_SCOPE))
            store.saveAccessToken(clientId, "access-1", Long.MAX_VALUE)

            vault.lock()
            assertNull(store.accessToken(clientId))

            vault.unlock(TEST_VAULT_PASSWORD)
            assertEquals("access-1", store.accessToken(clientId)?.token)
        }

    @Test
    fun `a re-consent invalidates the access token minted from the old refresh token`() =
        runTest {
            val vault = unlockedCredentialVault()
            val store = GoogleDriveAccountStore(vault)
            store.saveSignIn(clientId, "refresh-1", setOf(DRIVE_FILE_SCOPE))
            store.saveAccessToken(clientId, "access-1", Long.MAX_VALUE)

            store.saveSignIn(clientId, "refresh-2", setOf(DRIVE_FILE_SCOPE))

            assertNull(store.accessToken(clientId))
        }

    @Test
    fun `another database vault does not see this one access token`() =
        runTest {
            val vault = unlockedCredentialVault()
            val store = GoogleDriveAccountStore(vault)
            store.saveSignIn(clientId, "refresh-1", setOf(DRIVE_FILE_SCOPE))
            store.saveAccessToken(clientId, "access-1", Long.MAX_VALUE)

            vault.bindToDatabase("/elsewhere/other.db")

            assertNull(store.accessToken(clientId))
        }
}
