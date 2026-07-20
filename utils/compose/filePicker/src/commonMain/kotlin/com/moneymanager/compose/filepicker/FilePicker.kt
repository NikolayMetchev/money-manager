package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable

/**
 * Creates and remembers a file picker launcher.
 *
 * @param mimeTypes List of MIME types to filter files (e.g., "text/csv", "text/plain")
 * @param onResult Callback invoked with the selected file result, or null if cancelled
 * @return A launcher that can be used to open the file picker dialog
 */
@Composable
expect fun rememberFilePicker(
    mimeTypes: List<String> = listOf("text/csv", "text/plain"),
    onResult: (FilePickerResult?) -> Unit,
): FilePickerLauncher

/**
 * Creates and remembers a file picker launcher that allows selecting multiple files at once.
 *
 * @param mimeTypes List of MIME types to filter files
 * @param onResult Callback invoked with the selected files (empty if cancelled or none selected)
 * @return A launcher that can be used to open the multi-file picker dialog
 */
@Composable
expect fun rememberMultipleFilePicker(
    mimeTypes: List<String> = listOf("text/csv", "text/plain"),
    onResult: (List<FilePickerResult>) -> Unit,
): MultipleFilePickerLauncher

expect class MultipleFilePickerLauncher {
    fun launch()
}

/**
 * Creates and remembers a file picker launcher that reads the selection as raw bytes rather than text
 * (e.g. `.xlsx`, where decoding as UTF-8 like [rememberFilePicker] would corrupt the file).
 *
 * @param mimeTypes List of MIME types to filter files
 * @param onResult Callback invoked with the selected file result, or null if cancelled
 * @return A launcher that can be used to open the file picker dialog
 */
@Composable
expect fun rememberBinaryFilePicker(
    mimeTypes: List<String>,
    onResult: (BinaryFilePickerResult?) -> Unit,
): BinaryFilePickerLauncher
