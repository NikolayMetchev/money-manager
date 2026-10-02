package com.moneymanager.credentialvault

import com.moneymanager.archive.ArchiveCodec
import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.InMemoryVaultStorage
import com.moneymanager.credentialvault.testing.TEST_VAULT_DATABASE_KEY
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialVaultImplTest {
    private val monzo = StoredApiCredential(strategyName = "Monzo", token = "secret-token", createdAtEpochMillis = 1)

    @Test
    fun `a bound vault with no file reports NoFile at the default path`() {
        val vault = inMemoryCredentialVault()

        assertEquals(VaultState.NoFile("$TEST_VAULT_DATABASE_KEY.credentials"), vault.state.value)
    }

    @Test
    fun `secrets survive lock and unlock`() =
        runTest {
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.update("test") { it.withApiCredential(monzo) }

            vault.lock()
            assertIs<VaultState.Locked>(vault.state.value)
            assertNull(vault.bundleOrNull())

            vault.unlock(TEST_VAULT_PASSWORD)
            assertEquals(monzo, vault.bundleOrNull()?.apiCredential("Monzo"))
        }

    @Test
    fun `a wrong password fails and leaves the vault locked`() =
        runTest {
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.lock()

            assertFailsWith<ArchiveDecryptionException> { vault.unlock("wrong") }
            assertIs<VaultState.Locked>(vault.state.value)
        }

    @Test
    fun `the file is encrypted, never plain text`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.update("test") { it.withApiCredential(monzo) }

            val raw =
                storage.files.values
                    .single()
                    .decodeToString()
            assertFalse("secret-token" in raw)
        }

    @Test
    fun `a new vault over the same storage finds secrets saved before - the wiped database case`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val settings = InMemoryLocalSettings()
            val first = inMemoryCredentialVault(storage, settings)
            first.create(first.defaultPath()!!, TEST_VAULT_PASSWORD)
            first.update("test") { it.withApiCredential(monzo) }

            val second = inMemoryCredentialVault(storage, settings)
            assertIs<VaultState.Locked>(second.state.value)
            second.unlock(TEST_VAULT_PASSWORD)
            assertEquals(monzo, second.bundleOrNull()?.apiCredential("Monzo"))
        }

    @Test
    fun `binding to another database locks and forgets the password`() =
        runTest {
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)

            vault.bindToDatabase("/elsewhere/other.db")

            assertEquals(VaultState.NoFile("/elsewhere/other.db.credentials"), vault.state.value)
            vault.bindToDatabase(TEST_VAULT_DATABASE_KEY)
            assertIs<VaultState.Locked>(vault.state.value)
        }

    @Test
    fun `a custom location is remembered per database, the password is not`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val settings = InMemoryLocalSettings()
            val vault = inMemoryCredentialVault(storage, settings)
            vault.create("/custom/place.credentials", TEST_VAULT_PASSWORD)

            assertTrue(settings.values.values.none { TEST_VAULT_PASSWORD in it })
            val reopened = inMemoryCredentialVault(storage, settings)
            assertEquals(VaultState.Locked("/custom/place.credentials"), reopened.state.value)
        }

    @Test
    fun `requireUnlocked waits for the user to unlock`() =
        runTest {
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.update("test") { it.withApiCredential(monzo) }
            vault.lock()

            val waiting = async { vault.requireUnlocked("Download Monzo") }
            assertEquals("Download Monzo", vault.unlockRequest.first { it != null }?.reason)
            vault.unlock(TEST_VAULT_PASSWORD)

            assertEquals(monzo, waiting.await().apiCredential("Monzo"))
            assertNull(vault.unlockRequest.value)
        }

    @Test
    fun `cancelling the unlock request fails the waiter`() =
        runTest {
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.lock()

            val waiting = async { runCatching { vault.requireUnlocked("Download Monzo") } }
            vault.unlockRequest.first { it != null }
            vault.cancelUnlockRequest()

            assertIs<CredentialVaultLockedException>(waiting.await().exceptionOrNull())
        }

    @Test
    fun `a failed transform leaves the stored secrets untouched`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.update("test") { it.withApiCredential(monzo) }
            val before = storage.files.values.single()

            assertFailsWith<IllegalStateException> { vault.update("test") { error("boom") } }

            assertTrue(before.contentEquals(storage.files.values.single()))
            assertEquals(monzo, vault.bundleOrNull()?.apiCredential("Monzo"))
        }

    @Test
    fun `move and change password keep the secrets`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.update("test") { it.withApiCredential(monzo) }

            vault.moveTo("/moved/vault.credentials")
            vault.changePassword("new-password")
            vault.lock()

            assertEquals(setOf("/moved/vault.credentials"), storage.files.keys)
            vault.unlock("new-password")
            assertEquals(monzo, vault.bundleOrNull()?.apiCredential("Monzo"))
        }

    @Test
    fun `a file from a newer version is refused rather than misread`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            val newer = """{"version":${CredentialBundle.CURRENT_VERSION + 1}}"""
            storage.writeAtomically(vault.defaultPath()!!, ArchiveCodec.pack(newer.encodeToByteArray(), TEST_VAULT_PASSWORD))

            assertFailsWith<ArchiveDecryptionException> { vault.unlock(TEST_VAULT_PASSWORD) }
        }

    @Test
    fun `the version is always written`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)

            val json = ArchiveCodec.unpack(storage.files.values.single(), TEST_VAULT_PASSWORD).decodeToString()
            assertTrue("\"version\":${CredentialBundle.CURRENT_VERSION}" in json, json)
        }

    @Test
    fun `toString never reveals secrets`() {
        val bundle =
            CredentialBundle(
                apiCredentials = listOf(monzo.copy(apiSecret = "hmac-value")),
                googleAccounts = listOf(StoredGoogleAccount("client", "refresh-value")),
            )

        val text = bundle.toString()

        assertFalse("secret-token" in text || "hmac-value" in text || "refresh-value" in text)
    }

    @Test
    fun `merging keeps both sides with the newer API credential and the local Google account`() {
        val oldMonzo = monzo.copy(token = "old", createdAtEpochMillis = 1)
        val newMonzo = monzo.copy(token = "new", createdAtEpochMillis = 2)
        val wise = StoredApiCredential(strategyName = "Wise", token = "wise", createdAtEpochMillis = 1)
        val local =
            CredentialBundle(
                apiCredentials = listOf(oldMonzo),
                googleAccounts = listOf(StoredGoogleAccount("client", "local-refresh")),
            )
        val remote =
            CredentialBundle(
                apiCredentials = listOf(newMonzo, wise),
                googleAccounts = listOf(StoredGoogleAccount("client", "remote-refresh"), StoredGoogleAccount("other", "other-refresh")),
            )

        val merged = local.mergedWith(remote)

        assertEquals(newMonzo, merged.apiCredential("Monzo"))
        assertEquals(wise, merged.apiCredential("Wise"))
        assertEquals("local-refresh", merged.googleAccount("client")?.refreshToken)
        assertEquals("other-refresh", merged.googleAccount("other")?.refreshToken)
    }

    @Test
    fun `encryptedBytes returns the file as stored or null before it exists`() =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            assertNull(vault.encryptedBytes())

            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.lock()

            assertTrue(
                storage.files.values
                    .single()
                    .contentEquals(vault.encryptedBytes()),
            )
        }

    @Test
    fun `applying a remote copy replaces or merges the secrets`() =
        runTest {
            val wise = StoredApiCredential(strategyName = "Wise", token = "wise", createdAtEpochMillis = 1)
            val remote = ArchiveCodec.pack(json(CredentialBundle(apiCredentials = listOf(wise))), TEST_VAULT_PASSWORD)
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)
            vault.update("test") { it.withApiCredential(monzo) }

            vault.applyRemote(remote, password = null, merge = true)
            assertEquals(
                setOf("Monzo", "Wise"),
                vault
                    .bundleOrNull()
                    ?.apiCredentials
                    ?.map { it.strategyName }
                    ?.toSet(),
            )

            vault.applyRemote(remote, password = null, merge = false)
            assertEquals(listOf(wise), vault.bundleOrNull()?.apiCredentials)
        }

    @Test
    fun `applying a remote copy under another password adopts that password`() =
        runTest {
            val remote = ArchiveCodec.pack(json(CredentialBundle()), "remote-password")
            val vault = inMemoryCredentialVault()
            vault.create(vault.defaultPath()!!, TEST_VAULT_PASSWORD)

            assertFailsWith<ArchiveDecryptionException> { vault.applyRemote(remote, password = null, merge = true) }
            vault.applyRemote(remote, password = "remote-password", merge = true)
            vault.lock()

            assertFailsWith<ArchiveDecryptionException> { vault.unlock(TEST_VAULT_PASSWORD) }
            vault.unlock("remote-password")
        }

    private fun json(bundle: CredentialBundle): ByteArray = Json.encodeToString(bundle).encodeToByteArray()
}
