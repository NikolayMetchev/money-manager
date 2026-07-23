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

/**
 * Result of a binary file picker operation (e.g. `.xlsx`), where decoding the bytes as UTF-8 text
 * (like [FilePickerResult]) would corrupt the file.
 *
 * @property fileName The name of the selected file
 * @property bytes The raw content of the file
 * @property lastModified The last-modified timestamp of the file, if available
 */
data class BinaryFilePickerResult(
    val fileName: String,
    val bytes: ByteArray,
    val lastModified: Instant? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is BinaryFilePickerResult &&
                    fileName == other.fileName &&
                    bytes.contentEquals(other.bytes) &&
                    lastModified == other.lastModified
            )

    override fun hashCode(): Int = 31 * (31 * fileName.hashCode() + bytes.contentHashCode()) + lastModified.hashCode()
}
