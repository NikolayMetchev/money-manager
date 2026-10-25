package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.table.table
import com.moneymanager.csvimporter.readPdf
import com.moneymanager.files.expandTilde
import com.moneymanager.files.isRegularFile
import com.moneymanager.files.readBytes
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
internal data class PdfLineJson(
    val page: Int,
    val line: Int,
    val text: String,
)

/** `mm pdf-lines`: a PDF's text lines, as a CSV strategy's PDF line rules read them. Needs no database. */
internal class PdfLinesCommand : SuspendingCliktCommand(name = "pdf-lines") {
    private val runtime by requireObject<CliRuntime>()
    private val file by argument("FILE", help = "The PDF to read")

    override fun help(context: Context): String =
        """
        Print a PDF's text lines (page, line number, text), as a strategy's PDF line rules see them.

        Columns stay two or more spaces apart, so a Capture pattern can split them on \s{2,}.
        """.trimIndent()

    override suspend fun run() {
        val path = Path(expandTilde(file))
        if (!isRegularFile(path)) throw UsageError("No such file: $file")
        val lines = readPdf(readBytes(path)).lines.map { PdfLineJson(it.page, it.line, it.text) }
        runtime.output.emit(lines, ListSerializer(PdfLineJson.serializer())) { value ->
            table {
                header { row("Page", "Line", "Text") }
                body { value.forEach { row(it.page, it.line, it.text) } }
            }
        }
    }
}
