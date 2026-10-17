package com.moneymanager.tui

import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.files.writeText
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ImportsScreenTest {
    @Test
    fun importsTheDirectoriesAndListsWhatNeedsAPerson() {
        val location = createTestDatabaseLocation()
        val folder = Path(SystemTemporaryDirectory, "mm-tui-imports-${Uuid.random()}")
        SystemFileSystem.createDirectories(folder)
        writeText(Path(folder, "unknown.csv"), "Foo,Bar\n1,2\n")
        try {
            tui(location, DEFAULT_SCREENS) { _, controller ->
                screen { it.contains("o open another database") }
                val session = checkNotNull(controller.session)
                val now = Clock.System.now()
                session.importEngine.createImportDirectory(
                    ImportDirectory(
                        id = ImportDirectoryId(Uuid.random()),
                        name = "Inbox",
                        provider = ImportDirectoryProvider.LOCAL,
                        folderRef = folder.toString(),
                        deviceId = session.services.deviceId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )

                press("4")
                assertContains(screen { it.contains("Nothing imported yet") }, "d directories")
                press("d")
                val done = screen { it.contains("Needs attention · 1") }
                assertContains(done, "✓ Import directories")
                assertContains(done, "unknown.csv · no strategy matches it (noStrategy)")
                assertContains(done, "Resolve these in the desktop app")

                // The run outlives the tab: another tab and back still shows it.
                press("1")
                screen { !it.contains("Needs attention") }
                press("4")
                screen { it.contains("Needs attention · 1") }
            }
        } finally {
            deleteTestDatabase(location)
            SystemFileSystem.list(folder).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(folder)
        }
    }

    @Test
    fun aSignInUrlStaysOnScreenUntilDismissed() {
        val location = createTestDatabaseLocation()
        try {
            tui(location, DEFAULT_SCREENS) { _, controller ->
                screen { it.contains("o open another database") }
                controller.prompter.inform("Sign in to Google", "Open this URL:\nhttps://accounts.example.com/o/oauth2/auth?client_id=abc")
                assertContains(screen { it.contains("Sign in to Google") }, "https://accounts.example.com/o/oauth2/auth?client_id=abc")
                // Every key goes to the dialog until it's closed.
                press("2")
                assertContains(screen(), "Sign in to Google")
                press("Escape")
                assertFalse(screen { !it.contains("Sign in to Google") }.contains("client_id"))
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun wrapBreaksAtSpacesAndMidWordOnlyWhenItMust() {
        assertEquals(listOf("open this", "URL:", "https://a.", "example/x"), wrap("open this URL:\nhttps://a.example/x", 10))
        assertEquals(listOf(""), wrap("", 10))
    }
}
