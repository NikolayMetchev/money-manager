package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Android folder picking via the Storage Access Framework yields a content-tree URI, which the
 * filesystem-based local import backend cannot read. Until tree-URI support is added, the launcher is a
 * no-op (returns null) and the UI falls back to manual path entry.
 */
@Composable
actual fun rememberFolderPicker(onResult: (String?) -> Unit): FolderPickerLauncher = remember(onResult) { FolderPickerLauncher(onResult) }

actual class FolderPickerLauncher(
    private val onResult: (String?) -> Unit,
) {
    actual fun launch() {
        onResult(null)
    }
}
