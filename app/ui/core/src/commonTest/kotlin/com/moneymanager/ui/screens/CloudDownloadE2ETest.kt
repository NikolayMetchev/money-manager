package com.moneymanager.ui.screens

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import com.moneymanager.remotestorage.testing.InMemoryStorageProvider
import com.moneymanager.remotestorage.testing.SingleProviderFactory
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.upsertCurrencyByCode
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Downloading a cloud-backed database writes over the working copy that is open, so the app has to close it first
 * and reopen what was downloaded. The browser refuses to overwrite an open database, and the other platforms would
 * keep showing the old one.
 */
@OptIn(ExperimentalTestApi::class)
class CloudDownloadE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let(::deleteTestDatabase)
    }

    @Test
    fun theLockedBannersDownloadReplacesTheOpenWorkingCopy() =
        downloadReplacesTheOpenWorkingCopy {
            onNodeWithText("Download").performClick()
        }

    @Test
    fun theCloudStorageCardsDownloadReplacesTheOpenWorkingCopy() =
        downloadReplacesTheOpenWorkingCopy {
            onNodeWithText("Settings").performClick()
            // The locked banner's Download comes first; the second is the card's.
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Download").fetchSemanticsNodes().size == 2 }
            onAllNodesWithText("Download")[1].performScrollTo().performClick()
        }

    /**
     * Opens a working copy that has a change the cloud copy doesn't, while another device has pushed, so [download]
     * has to discard it and reopen what it downloads.
     */
    private fun downloadReplacesTheOpenWorkingCopy(download: ComposeUiTest.() -> Unit) {
        runMoneyManagerComposeUiTest {
            val location = createTestDatabaseLocation().also { testDbLocation = it }
            val databaseManager = createTestDatabaseManager()
            val settings = InMemoryLocalSettings()
            val provider = InMemoryStorageProvider()
            val controller =
                RemoteDatabaseController(RemoteDatabaseSyncService(databaseManager, settings), SingleProviderFactory(provider))
            val vault = runBlocking { unlockedCredentialVault() }
            val sessions = DatabaseSessions(databaseManager, settings, vault, controller)

            runBlocking {
                val database = databaseManager.openDatabase(location)
                val component = openDatabaseComponent(database)
                val currencies = component.currencyRepository.getAllCurrencies().first()
                component.settingsRepository.setDefaultCurrencyId(currencies.first().id)
                component.settingsRepository.setSetupWizardCompleted(true)
                controller.createRemote(provider.id, null, "db.mmdb", location, database, "pw")
                // Another device pushes the same database, so the cloud copy is ahead of this one.
                val remote = provider.list().single()
                provider.externalPush(remote.id, provider.download(remote.id))
                // Only in the working copy: the download must bring back the cloud copy without it.
                component.currencyRepository.upsertCurrencyByCode("ZZZ", "Not uploaded")
                database.close()
            }

            var openDatabase: MoneyManagerDatabaseWrapper? = null
            var opens = 0
            setContent {
                AppStartupHost(
                    databaseManager = databaseManager,
                    appVersion = AppVersion("test"),
                    sessions = sessions,
                    onInfoLog = {},
                    onErrorLog = { _, _ -> },
                    remoteController = controller,
                    credentialVault = vault,
                    onDatabaseReady = { database, _ ->
                        openDatabase = database
                        if (database != null) opens++
                    },
                )
            }

            waitUntil(timeoutMillis = 15_000) { openDatabase != null }
            assertEquals(1, zzzCount(openDatabase))
            // As "Check remote for changes" would: Download is offered once the app has seen the cloud copy is ahead.
            runBlocking { controller.checkRemote(checkNotNull(openDatabase)) }
            waitUntilAtLeastOneExists(hasText("Download") and isEnabled(), timeoutMillis = 15_000)
            download()
            onNodeWithText("Discard local").performClick()

            waitUntil(timeoutMillis = 15_000) { opens == 2 }
            waitForIdle()
            assertEquals(0, zzzCount(openDatabase))
        }
    }

    private fun zzzCount(database: MoneyManagerDatabaseWrapper?): Long =
        runBlocking {
            checkNotNull(database)
                .rawQuery("SELECT COUNT(*) FROM currency WHERE code = 'ZZZ'") { it.getLong(0)!! }
                .awaitAsOne()
        }
}
