@file:Suppress("UnusedPrivateProperty") // False positive: mimeType and onResult are used

package com.moneymanager.compose.filepicker

import com.moneymanager.localsettings.KEY_LAST_DIRECTORY
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual class FileSaverLauncher(
    private val mimeType: String,
    private val onResult: () -> Unit,
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

            // Open in the directory of the previous selection
            localSettings.getString(KEY_LAST_DIRECTORY)?.let { fileDialog.directory = it }

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
                localSettings.putString(KEY_LAST_DIRECTORY, directory)
                writeFileContent(File(directory, file), content)
            }
            onResult()
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

/** Writes [content] to [file]; a failed write is swallowed, as no caller acts on the outcome. */
internal fun writeFileContent(
    file: File,
    content: String,
) {
    try {
        file.writeText(content, Charsets.UTF_8)
    } catch (_: Exception) {
        // Nothing to report back to.
    }
}
