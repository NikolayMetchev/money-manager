package com.moneymanager.importdirectory

import com.moneymanager.csvimporter.DirectoryRedownloadResult
import com.moneymanager.csvimporter.ScanResult
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DirectoryDownloadResultsTest {
    private fun dir(name: String) =
        ImportDirectory(
            id = ImportDirectoryId(Uuid.random()),
            name = name,
            provider = ImportDirectoryProvider.GDRIVE,
            folderRef = name,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private fun scan(
        downloaded: Int,
        failures: List<String> = emptyList(),
    ) = ScanResult(filesDownloaded = downloaded, filesUnchanged = 0, filesSkipped = 0, filesFailed = failures.size, failures = failures)

    @Test
    fun `download all summary counts files and folders and prefixes issues with the folder`() {
        val result =
            DownloadAllResult(
                created = 1,
                results = listOf(dir("a") to scan(2, listOf("x.csv: bad")), dir("b") to scan(3)),
                folderFailures = listOf("c — couldn't download this folder: offline"),
                elapsed = 65.seconds,
            )

        assertEquals("Created 1 new directory; downloaded 5 file(s); 1 file(s) failed; 1 folder(s) failed in 01:05.", result.summary())
        assertEquals(listOf("c — couldn't download this folder: offline", "a — x.csv: bad"), result.issues)
    }

    @Test
    fun `single folder summary mentions subfolders only for a top-level folder`() {
        assertEquals("a: downloaded 2 file(s); created 3 subfolder directories.", DirectoryDownloadResult(dir("a"), scan(2), 3).summary())
        assertEquals("a: downloaded 2 file(s), 1 failed.", DirectoryDownloadResult(dir("a"), scan(2, listOf("x")), null).summary())
    }

    @Test
    fun `redownload issues say what to do about skipped and changed files`() {
        val result = DirectoryRedownloadResult(replaced = listOf("a.csv"), blocked = listOf("b.csv"), reimportSuggested = listOf("c.csv"))

        assertEquals("Re-downloaded 1 file(s), 1 skipped.", result.summary())
        assertEquals(
            listOf(
                "b.csv: imported rows are not in the new copy; re-download with \"unimport\" ticked",
                "c.csv: columns changed; Re-import it to apply them to its transactions",
            ),
            result.issues,
        )
    }
}
