package com.moneymanager.importfilesource.localfolder

import com.moneymanager.files.absolutePath
import com.moneymanager.files.writeText
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalFolderImportFileSourceTest {
    private val dir = Path(absolutePath(SystemTemporaryDirectory), "import-folder-test-${Random.nextLong().toULong()}")

    @AfterTest
    fun cleanUp() = deleteRecursively(dir)

    @Test
    fun `lists only files sorted by name with size and modified time`() =
        runTest {
            writeText(Path(dir, "b.csv"), "bb")
            writeText(Path(dir, "a.csv"), "a")
            SystemFileSystem.createDirectories(Path(dir, "nested"))

            val entries = LocalFolderImportFileSource(dir.toString()).list()

            assertEquals(listOf("a.csv", "b.csv"), entries.map { it.ref })
            assertEquals(listOf(1L, 2L), entries.map { it.sizeBytes })
            assertTrue(entries.all { (it.lastModifiedEpochMs ?: 0) > 0 })
        }

    @Test
    fun `lists subfolders by absolute path`() =
        runTest {
            SystemFileSystem.createDirectories(Path(dir, "2024"))
            SystemFileSystem.createDirectories(Path(dir, "2023"))
            writeText(Path(dir, "a.csv"), "a")

            val subfolders = LocalFolderImportFileSource(dir.toString()).listSubfolders()

            assertEquals(listOf("2023", "2024"), subfolders.map { it.name })
            assertEquals(Path(dir, "2023").toString(), subfolders.first().ref)
        }

    @Test
    fun `downloads a file by its ref`() =
        runTest {
            writeText(Path(dir, "a.csv"), "x,y")

            assertContentEquals("x,y".encodeToByteArray(), LocalFolderImportFileSource(dir.toString()).download("a.csv"))
        }

    @Test
    fun `a missing folder or a file is an error not an empty folder`() =
        runTest {
            val missing = assertFailsWith<FileNotFoundException> { LocalFolderImportFileSource(dir.toString()).list() }
            assertTrue(missing.message!!.contains("does not exist"))

            val file = Path(dir, "a.csv")
            writeText(file, "a")
            val notFolder = assertFailsWith<FileNotFoundException> { LocalFolderImportFileSource(file.toString()).listSubfolders() }
            assertTrue(notFolder.message!!.contains("is not a folder"))
        }

    private fun deleteRecursively(path: Path) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
        SystemFileSystem.delete(path)
    }
}
