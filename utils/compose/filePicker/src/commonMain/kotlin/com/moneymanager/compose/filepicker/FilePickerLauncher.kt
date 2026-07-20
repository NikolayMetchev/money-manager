package com.moneymanager.compose.filepicker

/**
 * Platform-specific file picker launcher.
 */
expect class FilePickerLauncher {
    /**
     * Opens the file picker dialog.
     */
    fun launch()
}

/** Platform-specific file picker launcher that reads the selection as raw bytes (e.g. `.xlsx`). */
expect class BinaryFilePickerLauncher {
    /**
     * Opens the file picker dialog.
     */
    fun launch()
}
