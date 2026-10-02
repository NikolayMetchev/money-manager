package com.moneymanager.remotestorage.sync

import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.bundleOrNull
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CredentialSyncControllerTest {
    private val provider = InMemoryStorageProvider()
    private val monzo = StoredApiCredential(strategyName = "Monzo", token = "monzo", createdAtEpochMillis = 1)
    private val wise = StoredApiCredential(strategyName = "Wise", token = "wise", createdAtEpochMillis = 1)

    private suspend fun device(): CredentialSyncController =
        CredentialSyncController(SingleProviderFactory(provider), CredentialRemoteConnectionStore(InMemoryLocalSettings())).apply {
            connect(provider.id, config = null)
        }

    private suspend fun remoteBytes(): ByteArray = provider.download(provider.list().single().id)

    private fun CredentialVault.strategies(): Set<String> =
        bundleOrNull()
            ?.apiCredentials
            ?.map { it.strategyName }
            .orEmpty()
            .toSet()

    @Test
    fun `the first sync uploads the vault file as is`() =
        runTest {
            val vault = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzo)))
            val controller = device()

            controller.syncNow(vault)

            assertEquals(CredentialSyncStatus.IN_SYNC, controller.state.value.status)
            assertContentEquals(vault.encryptedBytes(), remoteBytes())
        }

    @Test
    fun `a local change is uploaded and an unchanged vault is left alone`() =
        runTest {
            val vault = unlockedCredentialVault()
            val controller = device()
            controller.syncNow(vault)
            val revision = provider.list().single().revisionId

            controller.syncNow(vault)
            assertEquals(revision, provider.list().single().revisionId)

            vault.update("test") { it.withApiCredential(monzo) }
            controller.syncNow(vault)
            assertContentEquals(vault.encryptedBytes(), remoteBytes())
        }

    @Test
    fun `a new device merges with the existing backup`() =
        runTest {
            val first = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzo)))
            device().syncNow(first)

            val second = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(wise)))
            device().syncNow(second)

            assertEquals(setOf("Monzo", "Wise"), second.strategies())
            assertContentEquals(second.encryptedBytes(), remoteBytes())
        }

    @Test
    fun `a remote-only change replaces the local secrets`() =
        runTest {
            val first = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzo, wise)))
            val firstController = device()
            firstController.syncNow(first)
            val second = unlockedCredentialVault()
            val secondController = device()
            secondController.syncNow(second)

            // Removing a secret on one device must reach the other rather than be merged back in.
            second.update("test") { it.copy(apiCredentials = listOf(wise)) }
            secondController.syncNow(second)
            firstController.syncNow(first)

            assertEquals(setOf("Wise"), first.strategies())
        }

    @Test
    fun `a backup under another password asks for it and then adopts it`() =
        runTest {
            device().syncNow(unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzo))))
            val second = inMemoryCredentialVault().apply { create(requireNotNull(defaultPath()), "second-password") }
            val controller = device()

            controller.syncNow(second)
            assertEquals(CredentialSyncStatus.NEEDS_REMOTE_PASSWORD, controller.state.value.status)
            assertNull(controller.state.value.message)

            controller.syncNow(second, remotePassword = "wrong")
            assertNotNull(controller.state.value.message)

            controller.syncNow(second, remotePassword = TEST_VAULT_PASSWORD)
            assertEquals(CredentialSyncStatus.IN_SYNC, controller.state.value.status)
            assertEquals(setOf("Monzo"), second.strategies())
            second.lock()
            second.unlock(TEST_VAULT_PASSWORD)
        }

    @Test
    fun `nothing happens while disconnected or locked`() =
        runTest {
            val vault = unlockedCredentialVault()
            val controller = device()
            controller.disconnect()
            controller.syncNow(vault)

            val locked = device()
            vault.lock()
            locked.syncNow(vault)

            assertEquals(emptyList(), provider.list())
            assertEquals(CredentialSyncStatus.IDLE, locked.state.value.status)
        }
}
