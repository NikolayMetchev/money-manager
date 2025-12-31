@file:Suppress("UnusedPrivateProperty") // False positive: mimeTypes and onResult are used

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
            val extensions = mimeTypesToExtensions(mimeTypes)

            if (extensions.isNotEmpty()) {
                fileDialog.filenameFilter =
                    FilenameFilter { _, name ->
                        matchesExtensions(name, extensions)
                    }
            }

            fileDialog.isVisible = true

            val directory = fileDialog.directory
            val file = fileDialog.file

            if (directory != null && file != null) {
                val selectedFile = File(directory, file)
                val result = readFileAsResult(selectedFile)
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
 * Converts MIME types to file extensions.
 */
internal fun mimeTypesToExtensions(mimeTypes: List<String>): List<String> =
    mimeTypes
        .flatMap { mimeType ->
            when (mimeType) {
                "text/csv" -> listOf(".csv")
                "text/plain" -> listOf(".txt", ".csv")
                "text/tab-separated-values" -> listOf(".tsv")
                "application/vnd.ms-excel" -> listOf(".csv", ".xls")
                else -> emptyList()
            }
        }.distinct()

/**
 * Checks if a filename matches any of the given extensions.
 */
internal fun matchesExtensions(
    fileName: String,
    extensions: List<String>,
): Boolean = extensions.any { ext -> fileName.lowercase().endsWith(ext) }

/**
 * Reads a file and returns a FilePickerResult, or null if reading fails.
 */
internal fun readFileAsResult(file: File): FilePickerResult? =
    try {
        val content = file.readText(Charsets.UTF_8)
        FilePickerResult(fileName = file.name, content = content)
    } catch (e: Exception) {
        null
    }
