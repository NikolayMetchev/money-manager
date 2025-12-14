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
