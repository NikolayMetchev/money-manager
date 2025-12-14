package com.moneymanager.compose.filepicker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FilePickerResultTest {
    @Test
    fun `FilePickerResult stores fileName and content`() {
        val result =
            FilePickerResult(
                fileName = "test.csv",
                content = "header1,header2\nvalue1,value2",
            )

        assertEquals("test.csv", result.fileName)
        assertEquals("header1,header2\nvalue1,value2", result.content)
    }

    @Test
    fun `FilePickerResult equality works correctly`() {
        val result1 = FilePickerResult(fileName = "file.csv", content = "data")
        val result2 = FilePickerResult(fileName = "file.csv", content = "data")
        val result3 = FilePickerResult(fileName = "other.csv", content = "data")
        val result4 = FilePickerResult(fileName = "file.csv", content = "different")

        assertEquals(result1, result2)
        assertNotEquals(result1, result3)
        assertNotEquals(result1, result4)
    }

    @Test
    fun `FilePickerResult copy works correctly`() {
        val original = FilePickerResult(fileName = "original.csv", content = "original content")

        val copiedWithNewName = original.copy(fileName = "copied.csv")
        assertEquals("copied.csv", copiedWithNewName.fileName)
        assertEquals("original content", copiedWithNewName.content)

        val copiedWithNewContent = original.copy(content = "new content")
        assertEquals("original.csv", copiedWithNewContent.fileName)
        assertEquals("new content", copiedWithNewContent.content)
    }

    @Test
    fun `FilePickerResult handles empty content`() {
        val result = FilePickerResult(fileName = "empty.csv", content = "")

        assertEquals("empty.csv", result.fileName)
        assertEquals("", result.content)
    }

    @Test
    fun `FilePickerResult handles special characters in content`() {
        val content = "name,value\n\"quoted, with comma\",123\nspecial: éàü,456"
        val result = FilePickerResult(fileName = "special.csv", content = content)

        assertEquals(content, result.content)
    }

    @Test
    fun `FilePickerResult destructuring works correctly`() {
        val result = FilePickerResult(fileName = "test.csv", content = "data")

        val (fileName, content) = result

        assertEquals("test.csv", fileName)
        assertEquals("data", content)
    }
}
