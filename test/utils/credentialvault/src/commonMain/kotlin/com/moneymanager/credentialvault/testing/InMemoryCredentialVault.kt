package com.moneymanager.credentialvault.testing

import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultImpl
import com.moneymanager.credentialvault.VaultPathResolver
import com.moneymanager.credentialvault.VaultStorage
import com.moneymanager.localsettings.LocalSettings

/** [VaultStorage] over a map, so tests exercise the real vault (and its encryption) without files. */
class InMemoryVaultStorage : VaultStorage {
    val files = mutableMapOf<String, ByteArray>()

    override fun exists(path: String): Boolean = path in files

    override fun read(path: String): ByteArray = files[path] ?: error("No file at $path")

    override fun writeAtomically(
        path: String,
        bytes: ByteArray,
    ) {
        files[path] = bytes
    }

    override fun delete(path: String) {
        files.remove(path)
    }
}

/** [LocalSettings] over a map. */
class InMemoryLocalSettings : LocalSettings {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

const val TEST_VAULT_DATABASE_KEY: String = "/test/money_manager.db"
const val TEST_VAULT_PASSWORD: String = "test-vault-password"

/** A real [CredentialVault] over in-memory storage, bound to [TEST_VAULT_DATABASE_KEY] but not yet created. */
fun inMemoryCredentialVault(
    storage: VaultStorage = InMemoryVaultStorage(),
    localSettings: LocalSettings = InMemoryLocalSettings(),
): CredentialVault =
    CredentialVaultImpl(storage, VaultPathResolver { "$it.credentials" }, localSettings).apply {
        bindToDatabase(TEST_VAULT_DATABASE_KEY)
    }

/** An in-memory vault that is already created and unlocked, seeded with [initial]. */
suspend fun unlockedCredentialVault(initial: CredentialBundle = CredentialBundle()): CredentialVault =
    inMemoryCredentialVault().apply {
        create(requireNotNull(defaultPath()), TEST_VAULT_PASSWORD)
        if (initial != CredentialBundle()) update("test setup") { initial }
    }
