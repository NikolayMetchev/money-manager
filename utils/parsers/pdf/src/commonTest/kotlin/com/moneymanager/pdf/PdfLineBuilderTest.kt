package com.moneymanager.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfLineBuilderTest {
    // A monospaced 5pt-wide font, so gaps are easy to count in characters.
    private fun word(
        text: String,
        x: Double,
        baseline: Double,
        width: Double = 5.0,
    ): List<PdfGlyph> =
        text.mapIndexed { i, c ->
            PdfGlyph(c.toString(), x + i * width, x + (i + 1) * width, baseline, size = 10.0)
        }

    private fun lines(vararg glyphs: List<PdfGlyph>): List<String> =
        PdfLineBuilder.build(listOf(PdfPage(1, glyphs.toList().flatten().shuffled()))).map { it.text }

    @Test
    fun `glyphs drawn in any order read top to bottom then left to right`() {
        val result =
            lines(
                word("second", 10.0, 680.0),
                word("first", 10.0, 700.0),
                word("third", 10.0, 660.0),
            )
        assertEquals(listOf("first", "second", "third"), result)
    }

    @Test
    fun `a word gap is one space and a column gap several`() {
        val result = lines(word("Jan", 0.0, 700.0), word("2", 20.0, 700.0), word("29.29", 100.0, 700.0))
        // "Jan" ends at 15: a 5pt gap (one character) then "2" ends at 25: a 75pt gap (15 characters).
        assertEquals(listOf("Jan 2" + " ".repeat(15) + "29.29"), result)
    }

    @Test
    fun `glyphs on a slightly different baseline share a line`() {
        val result = lines(word("PAYMENT", 0.0, 700.0), word("RECEIVED", 45.0, 701.5))
        assertEquals(listOf("PAYMENT  RECEIVED"), result)
    }

    @Test
    fun `kerned glyphs closer than a space stay one word`() {
        val result = lines(word("AB", 0.0, 700.0), word("C", 10.5, 700.0))
        assertEquals(listOf("ABC"), result)
    }

    @Test
    fun `a glyph drawn twice for bold appears once`() {
        val result = lines(word("CR", 0.0, 700.0), word("CR", 0.2, 700.0))
        assertEquals(listOf("CR"), result)
    }

    @Test
    fun `whitespace glyphs are dropped and spacing comes from positions`() {
        val space = PdfGlyph(" ", 15.0, 20.0, 700.0, 10.0)
        val result = lines(word("Jan", 0.0, 700.0), listOf(space), word("2", 20.0, 700.0))
        assertEquals(listOf("Jan 2"), result)
    }

    @Test
    fun `lines are numbered per page`() {
        val pages =
            listOf(
                PdfPage(1, word("a", 0.0, 700.0) + word("b", 0.0, 680.0)),
                PdfPage(2, word("c", 0.0, 700.0)),
                PdfPage(3, emptyList()),
            )
        assertEquals(
            listOf(PdfLine(1, 1, "a"), PdfLine(1, 2, "b"), PdfLine(2, 1, "c")),
            PdfLineBuilder.build(pages),
        )
    }
}
