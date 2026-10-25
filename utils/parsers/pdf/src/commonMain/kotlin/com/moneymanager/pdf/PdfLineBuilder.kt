package com.moneymanager.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One line of a page's text; [page] and [number] are 1-based. */
data class PdfLine(
    val page: Int,
    val number: Int,
    val text: String,
)

/**
 * Rebuilds a PDF's text as lines from its glyphs' positions, as `pdftotext -layout` does: glyphs sharing a baseline
 * form a line, read left to right, and the gap between two glyphs becomes as many spaces as character widths fit in
 * it. A word gap is one space and a column gap several, so a pattern can split a statement's columns on `\s{2,}`.
 */
object PdfLineBuilder {
    /** How far apart two baselines can be, as a fraction of the font size, and still be one line. */
    private const val BASELINE_TOLERANCE = 0.35

    /** A gap narrower than this fraction of a character width is kerning, not a space. */
    private const val SPACE_THRESHOLD = 0.25

    /** Two copies of one glyph closer than this fraction of a character width are a faked bold, drawn twice. */
    private const val DUPLICATE_THRESHOLD = 0.1

    fun build(pages: List<PdfPage>): List<PdfLine> = pages.flatMap(::pageLines)

    private fun pageLines(page: PdfPage): List<PdfLine> {
        val glyphs = page.glyphs.filter { it.text.isNotBlank() }
        if (glyphs.isEmpty()) return emptyList()
        val unit = characterWidth(glyphs)
        val rows = mutableListOf<MutableList<PdfGlyph>>()
        var rowBaseline = 0.0
        for (glyph in glyphs.sortedWith(compareByDescending<PdfGlyph> { it.baseline }.thenBy { it.left })) {
            val row = rows.lastOrNull()
            if (row != null && abs(rowBaseline - glyph.baseline) <= max(glyph.size, 1.0) * BASELINE_TOLERANCE) {
                row += glyph
            } else {
                rows += mutableListOf(glyph)
                rowBaseline = glyph.baseline
            }
        }
        return rows.mapIndexed { index, row -> PdfLine(page.number, index + 1, rowText(row, unit)) }
    }

    private fun rowText(
        row: List<PdfGlyph>,
        unit: Double,
    ): String {
        val text = StringBuilder()
        var previous: PdfGlyph? = null
        var right = 0.0
        for (glyph in row.sortedBy { it.left }) {
            if (previous != null) {
                if (glyph.text == previous.text && abs(glyph.left - previous.left) < unit * DUPLICATE_THRESHOLD) continue
                val gap = glyph.left - right
                if (gap > unit * SPACE_THRESHOLD) text.append(" ".repeat(max(1, (gap / unit).roundToInt())))
            }
            text.append(glyph.text)
            right = if (previous == null) glyph.right else max(right, glyph.right)
            previous = glyph
        }
        return text.toString()
    }

    /** The page's typical character width: the median glyph width, or half the median font size if none has one. */
    private fun characterWidth(glyphs: List<PdfGlyph>): Double {
        val widths = glyphs.map { it.right - it.left }.filter { it > 0 }.sorted()
        if (widths.isNotEmpty()) return widths[widths.size / 2]
        val sizes = glyphs.map { it.size }.filter { it > 0 }.sorted()
        return if (sizes.isNotEmpty()) sizes[sizes.size / 2] / 2 else 1.0
    }
}
