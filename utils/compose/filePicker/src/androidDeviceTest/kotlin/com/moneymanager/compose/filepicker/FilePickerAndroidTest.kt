package com.moneymanager.compose.filepicker

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class FilePickerAndroidTest {
    @Test
    fun readStreamAsString_readsContentCorrectly() {
        val content = "header1,header2\nvalue1,value2"
        val inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

        val result = readStreamAsString(inputStream)

        assertEquals(content, result)
    }

    @Test
    fun readStreamAsString_handlesEmptyStream() {
        val inputStream = ByteArrayInputStream(ByteArray(0))

        val result = readStreamAsString(inputStream)

        assertEquals("", result)
    }

    @Test
    fun readStreamAsString_handlesUtf8Characters() {
        val content = "name,city\nJosé,São Paulo\n日本語,東京\némoji,🎉"
        val inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

        val result = readStreamAsString(inputStream)

        assertEquals(content, result)
    }

    @Test
    fun readStreamAsString_handlesMultilineContent() {
        val content =
            """
            header1,header2,header3
            row1col1,row1col2,row1col3
            row2col1,row2col2,row2col3
            row3col1,row3col2,row3col3
            """.trimIndent()
        val inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

        val result = readStreamAsString(inputStream)

        assertEquals(content, result)
    }

    @Test
    fun readStreamAsString_handlesWindowsLineEndings() {
        val content = "line1\r\nline2\r\nline3"
        val inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

        val result = readStreamAsString(inputStream)

        assertEquals(content, result)
    }

    @Test
    fun readStreamAsString_handlesLargeContent() {
        val line = "column1,column2,column3,column4,column5\n"
        val content = line.repeat(1000)
        val inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

        val result = readStreamAsString(inputStream)

        assertEquals(content, result)
    }
}
