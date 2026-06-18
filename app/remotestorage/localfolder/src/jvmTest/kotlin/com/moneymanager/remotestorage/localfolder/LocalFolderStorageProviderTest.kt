package com.moneymanager.remotestorage.localfolder

import com.moneymanager.remotestorage.RemoteStorageException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalFolderStorageProviderTest {
    private val tempDir = Files.createTempDirectory("localfolder-test").toFile()
    private val provider = LocalFolderStorageProvider(tempDir)

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun uploadDownloadListAndDeleteRoundTrip() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val uploaded = provider.upload(fileId = null, name = "db.mmdb", bytes = bytes)
            assertEquals("db.mmdb", uploaded.id)

            assertEquals(1, provider.list().size)
            assertContentEquals(bytes, provider.download(uploaded.id))

            // Re-upload to the same id overwrites in place.
            val updated = byteArrayOf(9, 9)
            provider.upload(fileId = uploaded.id, name = "db.mmdb", bytes = updated)
            assertContentEquals(updated, provider.download(uploaded.id))
            assertEquals(1, provider.list().size)

            provider.delete(uploaded.id)
            assertEquals(0, provider.list().size)
        }

    @Test
    fun downloadMissingFileFails() =
        runTest {
            assertFailsWith<RemoteStorageException> { provider.download("nope.mmdb") }
        }

    @Test
    fun forPathExpandsLeadingTildeToUserHome() =
        runTest {
            val home = System.getProperty("user.home")
            val uniqueDir = "mm-tilde-test-${System.nanoTime()}"
            val tildeProvider = LocalFolderStorageProvider.forPath("~/$uniqueDir")
            try {
                tildeProvider.upload(fileId = null, name = "db.mmdb", bytes = byteArrayOf(7))
                // Written under the real home directory, not a literal "~" folder in the working dir.
                assertTrue(File(home, "$uniqueDir/db.mmdb").isFile)
                assertTrue(!File("~/$uniqueDir").exists())
            } finally {
                File(home, uniqueDir).deleteRecursively()
                File("~").deleteRecursively()
            }
        }
}
