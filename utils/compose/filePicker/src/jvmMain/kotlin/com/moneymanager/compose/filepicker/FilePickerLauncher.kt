@file:Suppress("UnusedPrivateProperty") // False positive: mimeTypes and onResult are used

package com.moneymanager.compose.filepicker

import com.moneymanager.localsettings.KEY_LAST_DIRECTORY
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.localsettings.desktopLocalSettings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileFilter
import kotlin.time.Instant

/** Remembers the directory last used in a file dialog so subsequent dialogs reopen there. */
internal val localSettings: LocalSettings = desktopLocalSettings

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

actual class MultipleBinaryFilePickerLauncher(
    private val mimeTypes: List<String>,
    private val onResult: (List<BinaryFilePickerResult>) -> Unit,
) {
    actual fun launch() {
        val files = showLoadDialog("Select files", mimeTypes, multiple = true)
        files.firstOrNull()?.parent?.let { localSettings.putString(KEY_LAST_DIRECTORY, it) }
        onResult(files.mapNotNull { readFileAsBinaryResult(it) })
    }
}

/**
 * Shows a load dialog filtered by [mimeTypes], opened at the last-used directory, and returns
 * the selected files (empty if the user cancelled).
 */
private fun showLoadDialog(
    title: String,
    mimeTypes: List<String>,
    multiple: Boolean,
): List<File> {
    val extensions = mimeTypesToExtensions(mimeTypes)
    val lastDirectory = localSettings.getString(KEY_LAST_DIRECTORY)
    // AWT's Linux file dialog has no per-dialog control over hidden entries.
    if (System.getProperty("os.name") == "Linux") {
        val chooser = linuxLoadChooser(title, lastDirectory, extensions, multiple)
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return if (multiple) chooser.selectedFiles.toList() else listOfNotNull(chooser.selectedFile)
    }

    val frame = Frame()
    try {
        val fileDialog = FileDialog(frame, title, FileDialog.LOAD)
        fileDialog.isMultipleMode = multiple
        lastDirectory?.let { fileDialog.directory = it }

        if (extensions.isNotEmpty()) {
            fileDialog.filenameFilter = FilenameFilter { _, name -> matchesExtensions(name, extensions) }
        }

        fileDialog.isVisible = true

        return fileDialog.files.orEmpty().toList()
    } finally {
        frame.dispose()
    }
}

internal fun linuxLoadChooser(
    title: String,
    directory: String?,
    extensions: List<String>,
    multiple: Boolean,
): JFileChooser =
    JFileChooser(directory).apply {
        dialogTitle = title
        isMultiSelectionEnabled = multiple
        isFileHidingEnabled = false
        if (extensions.isNotEmpty()) {
            isAcceptAllFileFilterUsed = false
            fileFilter =
                object : FileFilter() {
                    override fun accept(file: File): Boolean = file.isDirectory || matchesExtensions(file.name, extensions)

                    override fun getDescription(): String = extensions.joinToString(", ") { "*$it" }
                }
        }
    }

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
