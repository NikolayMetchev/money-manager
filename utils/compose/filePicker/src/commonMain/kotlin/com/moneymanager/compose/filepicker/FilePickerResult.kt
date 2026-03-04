@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.compose.filepicker

import kotlin.time.Instant

/**
 * Result of a file picker operation.
 *
 * @property fileName The name of the selected file
 * @property content The content of the file as a string
 * @property lastModified The last-modified timestamp of the file, if available
 */
data class FilePickerResult(
    val fileName: String,
    val content: String,
    val lastModified: Instant? = null,
)
