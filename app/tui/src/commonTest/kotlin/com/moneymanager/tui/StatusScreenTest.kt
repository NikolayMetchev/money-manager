package com.moneymanager.tui

import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlin.test.Test
import kotlin.test.assertContains

class StatusScreenTest {
    @Test
    fun showsTheDatabaseAndLocksAndUnlocksTheVault() {
        val location = createTestDatabaseLocation()
        try {
            tui(location, DEFAULT_SCREENS) { host, _ ->
                val local = screen { it.contains("o open another database") }
                assertContains(local, "Version    9.9.9-test")
                assertContains(local, "Database   ${location.path}")
                assertContains(local, "Cloud      not cloud-backed")
                assertContains(local, "Vault      no vault file yet")
                assertContains(local, "l create vault")

                val vault = host.vault
                vault.create(checkNotNull(vault.defaultPath()), TEST_VAULT_PASSWORD)
                screen { it.contains("Vault      unlocked") }
                press("l")
                val locked = screen { it.contains("Vault      locked") }
                assertContains(locked, "l unlock vault")
                assertContains(locked, "vault locked · ? help")

                // The vault asks whoever answers its requests (a dialog in `mm tui`; here, the test).
                press("l")
                screen { it.contains("Unlocking the vault…") }
                vault.unlock(TEST_VAULT_PASSWORD)
                assertContains(screen { it.contains("Vault      unlocked") }, "l lock vault")
            }
        } finally {
            deleteTestDatabase(location)
        }
    }
}
