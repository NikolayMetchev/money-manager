@file:Suppress("UnusedPrivateProperty") // False positive: mimeTypes and onResult are used

package com.moneymanager.compose.filepicker

import com.moneymanager.localsettings.JvmLocalSettings
import com.moneymanager.localsettings.KEY_LAST_DIRECTORY
import com.moneymanager.localsettings.LocalSettings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import kotlin.time.Instant

/** Remembers the directory last used in a file dialog so subsequent dialogs reopen there. */
internal val localSettings: LocalSettings = JvmLocalSettings()

actual class FilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (FilePickerResult?) -> Unit,
) {
    actual fun launch() {
        val files = showLoadDialog("Select a file", mimeTypes, multiple = false)
        files.firstOrNull()?.parent?.let { localSettings.putString(KEY_LAST_DIRECTORY, it) }
        onResult(files.firstOrNull()?.let { readFileAsResult(it) })
    }
}

actual class MultipleFilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (List<FilePickerResult>) -> Unit,
) {
    actual fun launch() {
        val files = showLoadDialog("Select files", mimeTypes, multiple = true)
        files.firstOrNull()?.parent?.let { localSettings.putString(KEY_LAST_DIRECTORY, it) }
        onResult(files.mapNotNull { readFileAsResult(it) })
    }
}

actual class BinaryFilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (BinaryFilePickerResult?) -> Unit,
) {
    actual fun launch() {
        val files = showLoadDialog("Select a file", mimeTypes, multiple = false)
        files.firstOrNull()?.parent?.let { localSettings.putString(KEY_LAST_DIRECTORY, it) }
        onResult(files.firstOrNull()?.let { readFileAsBinaryResult(it) })
    }
}

/**
 * Shows a native load dialog filtered by [mimeTypes], opened at the last-used directory, and returns
 * the selected files (empty if the user cancelled).
 */
private fun showLoadDialog(
    title: String,
    mimeTypes: List<String>,
    multiple: Boolean,
): List<File> {
    val frame = Frame()
    try {
        val fileDialog = FileDialog(frame, title, FileDialog.LOAD)
        fileDialog.isMultipleMode = multiple
        localSettings.getString(KEY_LAST_DIRECTORY)?.let { fileDialog.directory = it }

        val extensions = mimeTypesToExtensions(mimeTypes)
        if (extensions.isNotEmpty()) {
            fileDialog.filenameFilter = FilenameFilter { _, name -> matchesExtensions(name, extensions) }
        }

        fileDialog.isVisible = true

        return fileDialog.files.orEmpty().toList()
    } finally {
        frame.dispose()
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
                "application/json" -> listOf(".json")
                "application/qif", "application/x-qif" -> listOf(".qif")
                "text/tab-separated-values" -> listOf(".tsv")
                "application/vnd.ms-excel" -> listOf(".csv", ".xls")
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> listOf(".xlsx")
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
        val lastModified = file.lastModified().takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
        FilePickerResult(fileName = file.name, content = content, lastModified = lastModified)
    } catch (_: Exception) {
        null
    }

/**
 * Reads a file as raw bytes and returns a BinaryFilePickerResult, or null if reading fails.
 */
internal fun readFileAsBinaryResult(file: File): BinaryFilePickerResult? =
    try {
        val bytes = file.readBytes()
        val lastModified = file.lastModified().takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
        BinaryFilePickerResult(fileName = file.name, bytes = bytes, lastModified = lastModified)
    } catch (_: Exception) {
        null
    }
