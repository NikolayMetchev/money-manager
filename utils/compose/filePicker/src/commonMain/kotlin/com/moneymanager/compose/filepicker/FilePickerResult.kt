package com.moneymanager.compose.filepicker

/**
 * Result of a file picker operation.
 *
 * @property fileName The name of the selected file
 * @property content The content of the file as a string
 */
data class FilePickerResult(
    val fileName: String,
    val content: String,
)
