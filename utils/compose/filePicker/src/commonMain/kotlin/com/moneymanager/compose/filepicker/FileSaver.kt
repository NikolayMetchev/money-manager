package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable

/**
 * Creates and remembers a file saver launcher.
 *
 * @param mimeType MIME type of the file to save (e.g., "application/json")
 * @param onResult Callback invoked once the save dialog closes, whether or not a file was written
 * @return A launcher that can be used to open the file save dialog
 */
@Composable
expect fun rememberFileSaver(
    mimeType: String = "application/json",
    onResult: () -> Unit,
): FileSaverLauncher
