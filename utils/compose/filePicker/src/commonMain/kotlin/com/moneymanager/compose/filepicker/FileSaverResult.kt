package com.moneymanager.compose.filepicker

/**
 * Result of a file save operation.
 *
 * @property success Whether the file was saved successfully
 * @property filePath The path where the file was saved, or null if cancelled/failed
 */
data class FileSaverResult(
    val success: Boolean,
    val filePath: String? = null,
)
