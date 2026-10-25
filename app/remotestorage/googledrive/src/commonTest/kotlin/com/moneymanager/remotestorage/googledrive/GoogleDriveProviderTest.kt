package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteStorageException
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleDriveProviderTest {
    private val drive = FakeDrive()
    private val provider = GoogleDriveProvider(drive.tokenSource)

    @Test
    fun firstUploadCreatesTheAppFolderAndTagsTheFile() =
        runTest {
            val uploaded = provider.upload(null, "db.mmz", "archive".encodeToByteArray())

            val folder = drive.items.single { it.mimeType == FakeDrive.FOLDER }
            assertEquals(GOOGLE_DRIVE_FOLDER_NAME, folder.name)
            assertNull(folder.parent)
            assertEquals(mapOf("moneymanagerDb" to "true"), folder.appProperties)
            val file = drive.items.single { it.id == uploaded.id }
            assertEquals(folder.id, file.parent)
            assertEquals(mapOf("moneymanagerDb" to "true"), file.appProperties)
            assertEquals("db.mmz", uploaded.name)
            assertEquals(7L, uploaded.sizeBytes)
            assertEquals("rev1", uploaded.revisionId)
        }

    @Test
    fun reuploadUpdatesInPlaceAndKeepsTheId() =
        runTest {
            val first = provider.upload(null, "db.mmz", "one".encodeToByteArray())
            val second = provider.upload(first.id, "db-renamed.mmz", "second".encodeToByteArray())

            assertEquals(first.id, second.id)
            assertEquals("db-renamed.mmz", second.name)
            assertEquals("rev2", second.revisionId)
            assertEquals(HttpMethod.Patch, drive.requests.last().method)
            assertContentEquals("second".encodeToByteArray(), provider.download(first.id))
        }

    @Test
    fun anUpdateRefusedForAnotherAppsFileWritesOurOwnCopyWhichListPrefers() =
        runTest {
            val folder = drive.folder(GOOGLE_DRIVE_FOLDER_NAME)
            val foreign = drive.add("mappings.json", folder.id, bytes = "old".encodeToByteArray(), readOnly = true)
            assertEquals(listOf(foreign.id), provider.list().map { it.id })

            val saved = provider.upload(foreign.id, "mappings.json", "new".encodeToByteArray())

            assertTrue(saved.id != foreign.id)
            assertContentEquals("old".encodeToByteArray(), foreign.bytes)
            assertEquals(listOf(saved.id), provider.list().map { it.id })
            assertContentEquals("new".encodeToByteArray(), provider.download(saved.id))
        }

    @Test
    fun anUpdateRefusedForAnotherReasonStillFailsWithDrivesReason() =
        runTest {
            drive.failures["/upload/drive/v3/files/id9"] = HttpStatusCode.Forbidden
            val error = assertFailsWith<RemoteStorageException> { provider.upload("id9", "a.json", ByteArray(0)) }
            assertTrue(error.message!!.startsWith("Failed to upload to Google Drive (403"), error.message)
        }

    @Test
    fun listReturnsOnlyTheAppFolderContentsAndCachesTheFolder() =
        runTest {
            val folder = drive.folder(GOOGLE_DRIVE_FOLDER_NAME)
            drive.add("a.mmz", folder.id, bytes = ByteArray(3))
            drive.add("elsewhere.mmz", parent = "other")

            assertEquals(listOf("a.mmz"), provider.list().map { it.name })
            val folderLookups = { drive.requests.count { it.url.parameters["q"]?.contains("name = ") == true } }
            assertEquals(1, folderLookups())
            provider.list()
            assertEquals(1, folderLookups())
            provider.signOut()
            provider.list()
            assertEquals(2, folderLookups())
        }

    @Test
    fun aSubfolderProviderNestsUnderTheAppFolder() =
        runTest {
            val strategies = GoogleDriveProvider(drive.tokenSource, subfolderName = "Strategies")
            strategies.upload(null, "wise.json", "{}".encodeToByteArray())

            val app = drive.items.single { it.name == GOOGLE_DRIVE_FOLDER_NAME }
            val sub = drive.items.single { it.name == "Strategies" }
            assertEquals(app.id, sub.parent)
            assertEquals(sub.id, drive.items.single { it.name == "wise.json" }.parent)
            assertEquals(listOf("wise.json"), strategies.list().map { it.name })
            assertTrue(provider.list().none { it.name == "wise.json" })
        }

    @Test
    fun statReturnsTheFileOrNullWhenDriveRefuses() =
        runTest {
            val uploaded = provider.upload(null, "db.mmz", "x".encodeToByteArray())

            assertEquals(uploaded, provider.stat(uploaded.id))
            assertNull(provider.stat("missing"))
        }

    @Test
    fun downloadFailureIsARemoteStorageException() =
        runTest {
            val error = assertFailsWith<RemoteStorageException> { provider.download("missing") }
            assertTrue(error.message!!.contains("404"), error.message)
        }

    @Test
    fun deleteToleratesAMissingFileButNotOtherFailures() =
        runTest {
            val uploaded = provider.upload(null, "db.mmz", "x".encodeToByteArray())
            provider.delete(uploaded.id)
            assertTrue(drive.items.none { it.id == uploaded.id })
            provider.delete(uploaded.id)

            drive.failures["/drive/v3/files/boom"] = HttpStatusCode.InternalServerError
            val error = assertFailsWith<RemoteStorageException> { provider.delete("boom") }
            assertTrue(error.message!!.contains("500"), error.message)
        }

    @Test
    fun aFailedListIsARemoteStorageException() =
        runTest {
            drive.failures["/drive/v3/files"] = HttpStatusCode.Forbidden
            val error = assertFailsWith<RemoteStorageException> { provider.list() }
            assertEquals("Failed to find Drive folder (403)", error.message)
        }

    @Test
    fun signInStateComesFromTheTokenSource() =
        runTest {
            assertTrue(provider.isSignedIn())
            assertEquals(42L, provider.accessTokenExpiresAtEpochMs())
            provider.signOut()
            assertFalse(provider.isSignedIn())
            provider.signIn()
            assertTrue(provider.isSignedIn())
            assertEquals(GOOGLE_DRIVE_PROVIDER_ID, googleDriveProvider(drive.tokenSource).id)
        }
}
