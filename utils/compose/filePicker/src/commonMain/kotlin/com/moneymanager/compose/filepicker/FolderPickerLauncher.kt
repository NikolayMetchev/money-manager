package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable

/**
 * Creates and remembers a folder (directory) picker launcher. [onResult] receives the chosen folder's
 * absolute path, or null if cancelled. Used to configure a local import directory.
 */
@Composable
expect fun rememberFolderPicker(onResult: (String?) -> Unit): FolderPickerLauncher

expect class FolderPickerLauncher {
    fun launch()
}
