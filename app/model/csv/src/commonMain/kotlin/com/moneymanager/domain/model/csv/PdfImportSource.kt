package com.moneymanager.domain.model.csv

/** One line of a PDF's text, as the parser rebuilt it from the glyphs' positions; [page] and [line] are 1-based. */
data class PdfTextLine(
    val page: Int,
    val line: Int,
    val text: String,
)

/**
 * A PDF staged as a [CsvImport]: the file's bytes and the [lines] read from them, kept beside the staged rows.
 * The rows are the lines until a PDF strategy assembles them into its own rows, which it does again from
 * [lines] (no parser needed) whenever its line rules change. [stagedRulesHash] names the line rules the staged rows
 * were assembled with, null while the raw lines are staged. Absent for CSV, QIF and Excel imports.
 */
class PdfImportSource(
    val fileBytes: ByteArray,
    val lines: List<PdfTextLine>,
    val stagedRulesHash: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PdfImportSource &&
                    fileBytes.contentEquals(other.fileBytes) &&
                    lines == other.lines &&
                    stagedRulesHash == other.stagedRulesHash
            )

    override fun hashCode(): Int = (31 * fileBytes.contentHashCode() + lines.hashCode()) * 31 + stagedRulesHash.hashCode()
}
