package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// A browser gives a page no folder it can read again later, so the web app has no import directories.
@Composable
actual fun rememberFolderPicker(onResult: (PickedFolder?) -> Unit): FolderPickerLauncher =
    remember(onResult) { FolderPickerLauncher(onResult) }

actual val manualFolderEntrySupported: Boolean = false

actual class FolderPickerLauncher(
    private val onResult: (PickedFolder?) -> Unit,
) {
    actual fun launch() {
        onResult(null)
    }
}
