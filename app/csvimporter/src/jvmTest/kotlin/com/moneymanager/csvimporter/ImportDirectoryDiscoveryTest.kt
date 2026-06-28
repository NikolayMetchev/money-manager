package com.moneymanager.csvimporter

import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A node in an in-memory folder tree: the files directly in it and its immediate subfolders. */
private class FakeNode(
    val files: List<String>,
    val subfolders: List<ImportSubfolder>,
)

private class FakeTreeSource(
    private val node: FakeNode,
) : ImportFileSource {
    override suspend fun list(): List<ImportFileEntry> = node.files.map { ImportFileEntry(ref = it, name = it, lastModifiedEpochMs = 0) }

    override suspend fun listSubfolders(): List<ImportSubfolder> = node.subfolders

    override suspend fun download(fileRef: String): ByteArray = ByteArray(0)
}

class ImportDirectoryDiscoveryTest {
    @Test
    fun `discovers every folder with importable files across the full tree`() =
        runTest {
            val tree =
                mapOf(
                    "root" to FakeNode(files = emptyList(), subfolders = listOf(ImportSubfolder("a", "A"), ImportSubfolder("b", "B"))),
                    "a" to FakeNode(files = listOf("jan.csv"), subfolders = listOf(ImportSubfolder("a1", "Nested"))),
                    "a1" to FakeNode(files = listOf("feb.qif"), subfolders = emptyList()),
                    // Only an unsupported file -> excluded.
                    "b" to FakeNode(files = listOf("readme.txt"), subfolders = emptyList()),
                )

            val discovered =
                discoverImportableFolders(
                    rootFolderRef = "root",
                    rootDisplayPath = "Drive",
                    openFolder = { ref -> FakeTreeSource(tree.getValue(ref)) },
                )

            assertEquals(
                listOf(
                    "a" to "Drive / A",
                    "a1" to "Drive / A / Nested",
                ),
                discovered.map { it.folderRef to it.displayPath },
                "only folders that directly contain .csv/.qif files are discovered (root and txt-only folder excluded)",
            )
        }
}
