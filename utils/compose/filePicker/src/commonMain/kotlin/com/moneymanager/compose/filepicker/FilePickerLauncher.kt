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

/** Platform-specific multi-file picker launcher that reads the selection as raw bytes (e.g. `.xlsx`). */
expect class MultipleBinaryFilePickerLauncher {
    /**
     * Opens the file picker dialog.
     */
    fun launch()
}
