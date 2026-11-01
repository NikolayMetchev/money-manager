package com.moneymanager.ui.screens

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val FILE_NAME = "statement.csv"

private object OneFileFolder : ImportFileSourceFactory {
    override suspend fun create(directory: ImportDirectory): ImportFileSource =
        object : ImportFileSource {
            override suspend fun list(): List<ImportFileEntry> =
                listOf(ImportFileEntry(ref = FILE_NAME, name = FILE_NAME, lastModifiedEpochMs = 1_000))

            override suspend fun listSubfolders(): List<ImportSubfolder> = emptyList()

            override suspend fun download(fileRef: String): ByteArray = "Date,Amount\n2024-01-01,1.00\n".encodeToByteArray()
        }
}

private class FixedLocationDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val location: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = location
}

/**
 * The Files tab's directories panel opens by default only while there are no files. "Download all", in its header,
 * stages files, which must not fold an open panel away mid-run, and works from a folded panel too: the person
 * needs it to look for new files and folders again.
 */
@OptIn(ExperimentalTestApi::class)
class ImportDirectoriesPanelTest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { deleteTestDatabase(it) }
    }

    /** Opens the app on a database with one import folder (and, when [stagedFile] is given, one staged file). */
    private fun ComposeUiTest.openImportsOn(stagedFile: String? = null) {
        val location = createTestDatabaseLocation().also { testDbLocation = it }
        val databaseManager = createTestDatabaseManager()
        runBlocking {
            val component = openDatabaseComponent(databaseManager.openDatabase(location))
            val now = Clock.System.now()
            component.importDirectoryRepository.createDirectory(
                ImportDirectory(
                    id = ImportDirectoryId(Uuid.random()),
                    name = "Bank",
                    provider = ImportDirectoryProvider.LOCAL,
                    folderRef = "/fake/Bank",
                    deviceId = component.deviceId,
                    createdAt = now,
                    updatedAt = now,
                ),
                Source.Manual,
            )
            if (stagedFile != null) {
                component.csvImportRepository.createImport(
                    fileName = stagedFile,
                    headers = listOf("Date", "Amount"),
                    rows = listOf(listOf("2023-01-01", "2.00")),
                    fileChecksum = "$stagedFile-checksum",
                    fileLastModified = Instant.fromEpochMilliseconds(1_000),
                )
            }
        }

        setContent {
            MoneyManagerTestApp(
                databaseManager = FixedLocationDatabaseManager(databaseManager, location),
                appVersion = AppVersion("1.0.0-test"),
                importFileSourceFactory = OneFileFolder,
            )
        }

        waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)
        onNodeWithText("Imports", useUnmergedTree = true).performClick()
        waitUntilExactlyOneExists(hasText("Download all") and isEnabled(), timeoutMillis = 15000)
    }

    @Test
    fun downloadAll_keepsTheDirectoriesPanelOpen_onceFilesAreStaged() {
        runMoneyManagerComposeUiTest {
            openImportsOn()

            onNodeWithText("Download all").performClick()
            // The run's outcome is shown inside the panel, so seeing it means the panel stayed open.
            waitUntilExactlyOneExists(hasText("downloaded 1 file(s)", substring = true), timeoutMillis = 15000)
            waitForIdle()

            // And it can be run again, to look for new files and folders.
            waitUntilExactlyOneExists(hasText("Download all") and isEnabled(), timeoutMillis = 5000)
        }
    }

    @Test
    fun downloadAll_runsFromTheFoldedPanelsHeader() {
        runMoneyManagerComposeUiTest {
            openImportsOn(stagedFile = "earlier.csv")
            // With files already staged the panel starts folded, so its folders aren't shown.
            waitUntilAtLeastOneExists(hasText("earlier.csv"), timeoutMillis = 15000)
            waitUntilDoesNotExist(hasText("Add directory"), timeoutMillis = 5000)
            waitForIdle()

            onNodeWithText("Download all").performClick()
            waitUntilAtLeastOneExists(hasText(FILE_NAME), timeoutMillis = 15000)
            waitForIdle()

            // The panel stays folded, and "Download all" is ready to look again.
            onNodeWithText("Add directory").assertDoesNotExist()
            waitUntilExactlyOneExists(hasText("Download all") and isEnabled(), timeoutMillis = 5000)
        }
    }
}
