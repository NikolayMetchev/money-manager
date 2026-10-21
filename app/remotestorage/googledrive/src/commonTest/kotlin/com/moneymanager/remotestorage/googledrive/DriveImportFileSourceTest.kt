package com.moneymanager.remotestorage.googledrive

import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.remotestorage.RemoteStorageException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriveImportFileSourceTest {
    private val drive = FakeDrive(pageSize = 2)
    private val statements = drive.folder("Statements", parent = DriveImportFileSource.ROOT_FOLDER_ID)
    private val source = DriveImportFileSource(drive.tokenSource, statements.id)

    @Test
    fun listFollowsEveryPageAndSkipsFolders() =
        runTest {
            val a = drive.add("a.csv", statements.id, bytes = ByteArray(5), modifiedTime = "2026-01-02T03:04:05Z", md5Checksum = "md5a")
            drive.add("b.csv", statements.id, modifiedTime = "not a time")
            drive.add("c.qif", statements.id)
            drive.folder("2025", parent = statements.id)
            drive.add("elsewhere.csv", parent = "other")

            val entries = source.list()

            assertEquals(listOf("a.csv", "b.csv", "c.qif"), entries.map { it.name })
            assertEquals(
                ImportFileEntry(ref = a.id, name = "a.csv", lastModifiedEpochMs = 1767323045000, sizeBytes = 5, remoteContentHash = "md5a"),
                entries.first(),
            )
            assertEquals(null, entries[1].lastModifiedEpochMs)
            assertEquals(listOf(null, "2"), drive.requests.map { it.url.parameters["pageToken"] })
        }

    @Test
    fun subfoldersAreListedByName() =
        runTest {
            val zebra = drive.folder("zebra", parent = statements.id)
            val alpha = drive.folder("Alpha", parent = statements.id)
            drive.add("file.csv", statements.id)

            assertEquals(listOf(ImportSubfolder(alpha.id, "Alpha"), ImportSubfolder(zebra.id, "zebra")), source.listSubfolders())
        }

    @Test
    fun sharedWithMeIsItsOwnRoot() =
        runTest {
            drive.folder("Shared statements", parent = "someone-else", sharedWithMe = true)

            val shared = DriveImportFileSource.listChildFolders(drive.tokenSource, DriveImportFileSource.SHARED_WITH_ME_FOLDER_ID)
            assertEquals(listOf("Shared statements"), shared.map { it.name })
            val mine = DriveImportFileSource.listChildFolders(drive.tokenSource, DriveImportFileSource.ROOT_FOLDER_ID)
            assertEquals(listOf("Statements"), mine.map { it.name })
        }

    @Test
    fun downloadReturnsTheFileContent() =
        runTest {
            val file = drive.add("a.csv", statements.id, bytes = "date,amount".encodeToByteArray())

            assertContentEquals("date,amount".encodeToByteArray(), source.download(file.id))
            val params =
                drive.requests
                    .last()
                    .url.parameters
            assertEquals("true", params["acknowledgeAbuse"])
            assertEquals("true", params["supportsAllDrives"])
        }

    @Test
    fun aForbiddenDownloadExplainsTheLikelyCause() =
        runTest {
            drive.failures["/drive/v3/files/sheet"] = HttpStatusCode.Forbidden
            val forbidden = assertFailsWith<RemoteStorageException> { source.download("sheet") }
            assertTrue(forbidden.message!!.contains("Google Docs/Sheets"), forbidden.message)

            val missing = assertFailsWith<RemoteStorageException> { source.download("missing") }
            assertEquals("Failed to download from Google Drive (404)", missing.message)
        }

    @Test
    fun aFailedListIsARemoteStorageException() =
        runTest {
            drive.failures["/drive/v3/files"] = HttpStatusCode.Unauthorized
            val error = assertFailsWith<RemoteStorageException> { source.list() }
            assertEquals("Failed to list Google Drive (401)", error.message)
        }
}
