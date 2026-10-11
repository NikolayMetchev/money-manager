package com.moneymanager.credentialvault

import com.moneymanager.credentialvault.testing.InMemoryVaultStorage
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A vault file outlives the app that wrote it and is synced to Drive, so the desktop app and the native
 * CLI must read each other's. These fixtures were written once on the JVM and once on linuxX64, and
 * every platform must unlock both. Never regenerate them: a fixture that stops decoding means the
 * on-disk format changed.
 */
class CredentialVaultGoldenTest {
    @Test
    fun `a vault file written on the JVM unlocks here`() = assertUnlocks(JVM_VAULT)

    @Test
    fun `a vault file written on native unlocks here`() = assertUnlocks(NATIVE_VAULT)

    private fun assertUnlocks(fixture: String) =
        runTest {
            val storage = InMemoryVaultStorage()
            val vault = inMemoryCredentialVault(storage)
            storage.files[vault.defaultPath()!!] = Base64.decode(fixture.filterNot(Char::isWhitespace))

            vault.unlock(TEST_VAULT_PASSWORD)

            assertEquals(BUNDLE, vault.bundleOrNull())
        }

    companion object {
        val BUNDLE =
            CredentialBundle(
                apiCredentials =
                    listOf(
                        StoredApiCredential(
                            strategyName = "Kraken",
                            token = "api-key",
                            createdAtEpochMillis = 1_760_000_000_000,
                            apiSecret = "api-secret",
                        ),
                    ),
                googleAccounts =
                    listOf(
                        StoredGoogleAccount(
                            clientId = "client.apps.googleusercontent.com",
                            refreshToken = "refresh-token",
                            grantedScopes = setOf("https://www.googleapis.com/auth/drive.file"),
                        ),
                    ),
            )

        private const val JVM_VAULT =
            """
            TU1BUgHC73I+V0lgY4CYq1mq02WSHbm/mVmb00Z70+SuLPF272iXpx8ze1amwqzCOLoMX0S+4uF2NyvM/nsc8zDqiIgQOodVlNmP
            DCrAQ0nstQGeGw2fq0qadTptXPlF4KzrgqXFPJFQqbjeB/mzfizSgtqZk8mfHA/8tfj1guoa6tET0FpNt9odpzFVQY6MlCItPiMA
            ZYiFOww+GIhRGn2E/TmMaVEHPE43ysLK0EWPYvJ4M/SBSYD4GyPNZH5LpQklLBhYkTOAr1UkkJaB7AwKC8hay30b7zk92wIjCynD
            sfV5THRkcR5y/9HBbCzsXU41zmYTGl56dbDEYVyUhulQ2A==
            """

        private const val NATIVE_VAULT =
            """
            TU1BUgHdt9Mmg3Cr/FObQskPkAO/dGqDxnKAMeK5p5a9AE6HrMm1bnXXJo1IaJasgEY1iRnePb36VWXIvtc1XQqtsf450Tiu6ip5
            +aFv36PdaI2GlT3M9nBUSqD+xMqqqGCXQbP58O9CIgq3XYqIQ+4Ll3fbgqwfUVX5er6a6G90L0v1UItW1EmzLij8M6GyNftsbQM/
            PrVT3cUj308Nutd40eCqwtE9XK3ztbto7Jdb1WB7IKyY0LIxDIwRqZHB2rr6nCpgM32sQyvGTdF3B8HBWg2GUe5fj/vEbxIsURDw
            KyiprDAfdYo1C/0Artzle3+JAXsBad4ljHm8CFvyIC8AEg==
            """
    }
}
