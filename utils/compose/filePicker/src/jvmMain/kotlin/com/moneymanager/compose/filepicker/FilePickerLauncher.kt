package com.moneymanager.compose.filepicker

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

actual class FilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (FilePickerResult?) -> Unit,
) {
    actual fun launch() {
        openFileDialog()
    }

    private fun openFileDialog() {
        // Create a hidden frame for the dialog
        val frame = Frame()
        try {
            val fileDialog = FileDialog(frame, "Select a CSV file", FileDialog.LOAD)

            // Set file filter based on mime types
            val extensions =
                mimeTypes.flatMap { mimeType ->
                    when (mimeType) {
                        "text/csv" -> listOf(".csv")
                        "text/plain" -> listOf(".txt", ".csv")
                        else -> emptyList()
                    }
                }.distinct()

            if (extensions.isNotEmpty()) {
                fileDialog.filenameFilter =
                    FilenameFilter { _, name ->
                        extensions.any { ext -> name.lowercase().endsWith(ext) }
                    }
            }

            fileDialog.isVisible = true

            val directory = fileDialog.directory
            val file = fileDialog.file

            if (directory != null && file != null) {
                val selectedFile = File(directory, file)
                try {
                    val content = selectedFile.readText(Charsets.UTF_8)
                    onResult(FilePickerResult(fileName = file, content = content))
                } catch (e: Exception) {
                    // Failed to read file
                    onResult(null)
                }
            } else {
                // User cancelled
                onResult(null)
            }
        } finally {
            frame.dispose()
        }
    }
}
