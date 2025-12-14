package com.moneymanager.compose.filepicker

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePickerLauncherJvmTest {
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun createTempFile(
        name: String,
        content: String,
    ): File {
        val file = File.createTempFile(name, null)
        file.writeText(content, Charsets.UTF_8)
        tempFiles.add(file)
        return file
    }

    // MIME type to extensions tests

    @Test
    fun `mimeTypesToExtensions returns csv extension for text csv`() {
        val extensions = mimeTypesToExtensions(listOf("text/csv"))

        assertEquals(listOf(".csv"), extensions)
    }

    @Test
    fun `mimeTypesToExtensions returns txt and csv for text plain`() {
        val extensions = mimeTypesToExtensions(listOf("text/plain"))

        assertTrue(extensions.contains(".txt"))
        assertTrue(extensions.contains(".csv"))
    }

    @Test
    fun `mimeTypesToExtensions returns tsv for tab separated values`() {
        val extensions = mimeTypesToExtensions(listOf("text/tab-separated-values"))

        assertEquals(listOf(".tsv"), extensions)
    }

    @Test
    fun `mimeTypesToExtensions returns empty list for unknown mime type`() {
        val extensions = mimeTypesToExtensions(listOf("application/unknown"))

        assertTrue(extensions.isEmpty())
    }

    @Test
    fun `mimeTypesToExtensions deduplicates extensions`() {
        val extensions = mimeTypesToExtensions(listOf("text/csv", "text/plain"))

        // Both contain .csv, should only appear once
        assertEquals(1, extensions.count { it == ".csv" })
    }

    @Test
    fun `mimeTypesToExtensions handles multiple mime types`() {
        val extensions = mimeTypesToExtensions(listOf("text/csv", "text/tab-separated-values"))

        assertTrue(extensions.contains(".csv"))
        assertTrue(extensions.contains(".tsv"))
    }

    // Extension matching tests

    @Test
    fun `matchesExtensions returns true for matching extension`() {
        assertTrue(matchesExtensions("data.csv", listOf(".csv")))
        assertTrue(matchesExtensions("data.txt", listOf(".txt", ".csv")))
    }

    @Test
    fun `matchesExtensions is case insensitive`() {
        assertTrue(matchesExtensions("DATA.CSV", listOf(".csv")))
        assertTrue(matchesExtensions("Data.Csv", listOf(".csv")))
    }

    @Test
    fun `matchesExtensions returns false for non-matching extension`() {
        assertFalse(matchesExtensions("data.xlsx", listOf(".csv", ".txt")))
        assertFalse(matchesExtensions("data.json", listOf(".csv")))
    }

    @Test
    fun `matchesExtensions returns false for empty extensions list`() {
        assertFalse(matchesExtensions("data.csv", emptyList()))
    }

    // File reading tests

    @Test
    fun `readFileAsResult reads file content correctly`() {
        val content = "header1,header2\nvalue1,value2"
        val file = createTempFile("test", content)

        val result = readFileAsResult(file)

        assertNotNull(result)
        assertEquals(content, result.content)
    }

    @Test
    fun `readFileAsResult returns correct fileName`() {
        val file = createTempFile("myfile", "content")

        val result = readFileAsResult(file)

        assertNotNull(result)
        assertEquals(file.name, result.fileName)
    }

    @Test
    fun `readFileAsResult handles UTF-8 content`() {
        val content = "name,city\nJosé,São Paulo\n日本語,東京"
        val file = createTempFile("utf8test", content)

        val result = readFileAsResult(file)

        assertNotNull(result)
        assertEquals(content, result.content)
    }

    @Test
    fun `readFileAsResult handles empty file`() {
        val file = createTempFile("empty", "")

        val result = readFileAsResult(file)

        assertNotNull(result)
        assertEquals("", result.content)
    }

    @Test
    fun `readFileAsResult returns null for non-existent file`() {
        val file = File("/non/existent/path/file.csv")

        val result = readFileAsResult(file)

        assertNull(result)
    }

    @Test
    fun `readFileAsResult handles multiline content`() {
        val content =
            """
            header1,header2,header3
            row1col1,row1col2,row1col3
            row2col1,row2col2,row2col3
            """.trimIndent()
        val file = createTempFile("multiline", content)

        val result = readFileAsResult(file)

        assertNotNull(result)
        assertEquals(content, result.content)
    }
}
