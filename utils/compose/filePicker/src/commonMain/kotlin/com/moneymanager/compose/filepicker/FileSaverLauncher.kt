package com.moneymanager.compose.filepicker

/**
 * Platform-specific file saver launcher.
 */
expect class FileSaverLauncher {
    /**
     * Opens the file save dialog with the given file name and content.
     *
     * @param fileName Suggested file name (user may change it)
     * @param content The content to write to the file
     */
    fun launch(
        fileName: String,
        content: String,
    )
}
