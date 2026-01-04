@file:Suppress("UnusedPrivateProperty") // False positive: mimeType and onResult are used

package com.moneymanager.compose.filepicker

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual class FileSaverLauncher(
    private val mimeType: String,
    private val onResult: (FileSaverResult?) -> Unit,
) {
    actual fun launch(
        fileName: String,
        content: String,
    ) {
        openSaveDialog(fileName, content)
    }

    private fun openSaveDialog(
        fileName: String,
        content: String,
    ) {
        val frame = Frame()
        try {
            val fileDialog = FileDialog(frame, "Save file", FileDialog.SAVE)
            fileDialog.file = fileName

            // Set file filter based on mime type
            val extension = mimeTypeToExtension(mimeType)
            if (extension != null) {
                fileDialog.setFilenameFilter { _, name ->
                    name.lowercase().endsWith(extension)
                }
            }

            fileDialog.isVisible = true

            val directory = fileDialog.directory
            val file = fileDialog.file

            if (directory != null && file != null) {
                val targetFile = File(directory, file)
                val result = writeFileContent(targetFile, content)
                onResult(result)
            } else {
                // User cancelled
                onResult(null)
            }
        } finally {
            frame.dispose()
        }
    }
}

/**
 * Converts a MIME type to a file extension.
 */
internal fun mimeTypeToExtension(mimeType: String): String? =
    when (mimeType) {
        "application/json" -> ".json"
        "text/csv" -> ".csv"
        "text/plain" -> ".txt"
        else -> null
    }

/**
 * Writes content to a file and returns the result.
 */
internal fun writeFileContent(
    file: File,
    content: String,
): FileSaverResult =
    try {
        file.writeText(content, Charsets.UTF_8)
        FileSaverResult(success = true, filePath = file.absolutePath)
    } catch (_: Exception) {
        FileSaverResult(success = false)
    }
